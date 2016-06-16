/*
 * Copyright (C) 2016, BMW Car IT GmbH
 *
 * Author: Sebastian Mattheis <sebastian.mattheis@bmw-carit.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in
 * writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.bmwcarit.barefoot.tracker;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZMQ;

import com.bmwcarit.barefoot.matcher.Matcher;
import com.bmwcarit.barefoot.matcher.MatcherCandidate;
import com.bmwcarit.barefoot.matcher.MatcherKState;
import com.bmwcarit.barefoot.matcher.MatcherSample;
import com.bmwcarit.barefoot.roadmap.Road;
import com.bmwcarit.barefoot.roadmap.RoadMap;
import com.bmwcarit.barefoot.roadmap.RoadPoint;
import com.bmwcarit.barefoot.roadmap.TimePriority;
import com.bmwcarit.barefoot.scheduler.StaticScheduler;
import com.bmwcarit.barefoot.scheduler.StaticScheduler.InlineScheduler;
import com.bmwcarit.barefoot.scheduler.Task;
import com.bmwcarit.barefoot.spatial.Geography;
import com.bmwcarit.barefoot.topology.Dijkstra;
import com.bmwcarit.barefoot.tracker.TemporaryMemory.Factory;
import com.bmwcarit.barefoot.tracker.TemporaryMemory.Publisher;
import com.bmwcarit.barefoot.tracker.TemporaryMemory.TemporaryElement;
import com.bmwcarit.barefoot.util.AbstractServer;
import com.bmwcarit.barefoot.util.Stopwatch;

/**
 * Tracker server (stand-alone) for Hidden Markov Model online map matching. It is a
 * {@link AbstractServer} that performs online map matching; and is configurable with a properties
 * file. It pushes updates via a ZeroMQ publisher port to subscribing listeners.
 */
public class TrackerServer extends AbstractServer {
    private final static Logger logger = LoggerFactory.getLogger(TrackerServer.class);
    private final RoadMap map;

    /**
     * Creates a {@link TrackerServer} object as stand-alone online map matching server. The
     * provided {@link Properties} object may provide the following properties:
     * <ul>
     * <li>server properties: see {@link AbstractServer#AbstractServer(Properties, ResponseFactory)}
     * </li>
     * <li>matcher.radius.max (see {@link Matcher#setMaxRadius(double)})</li>
     * <li>matcher.distance.max (see {@link Matcher#setMaxDistance(double)})</li>
     * <li>matcher.lambda (see {@link Matcher#setLambda(double)})</li>
     * <li>matcher.sigma (see {@link Matcher#setSigma(double)})</li>
     * <li>tracker.port (optional, default: 1235)</li>
     * <li>tracker.ttl (seconds, optional, default: 60, sets time to live of state information for
     * tracked objects which is infinite if set to zero)</li>
     * </ul>
     *
     * @param properties {@link Properties} object with (optional) server and matcher settings.
     * @param map {@link RoadMap} object with the map to be matched with.
     */
    public TrackerServer(Properties properties, RoadMap map) {
        super(properties, new MatcherResponseFactory(properties, map));
        this.map = map;
    }

    /**
     * Gets {@link RoadMap} object of the server.
     *
     * @return {@link RoadMap} object of the server.
     */
    public RoadMap getMap() {
        return this.map;
    }

    /**
     * Gets {@link Matcher} object of the server.
     *
     * @return {@link Matcher} object of the server.
     */
    public Matcher getMatcher() {
        return ((MatcherResponseFactory) getResponseFactory()).matcher;
    }

    private static class MatcherResponseFactory extends ResponseFactory {
        private final Matcher matcher;
        private final int TTL;
        private final TemporaryMemory<State> memory;

        public MatcherResponseFactory(Properties properties, RoadMap map) {
            matcher =
                    new Matcher(map, new Dijkstra<Road, RoadPoint>(), new TimePriority(),
                            new Geography());

            matcher.setMaxRadius(Double.parseDouble(properties.getProperty("matcher.radius.max",
                    Double.toString(matcher.getMaxRadius()))));
            matcher.setMaxDistance(Double.parseDouble(properties.getProperty(
                    "matcher.distance.max", Double.toString(matcher.getMaxDistance()))));
            matcher.setLambda(Double.parseDouble(properties.getProperty("matcher.lambda",
                    Double.toString(matcher.getLambda()))));
            matcher.setSigma(Double.parseDouble(properties.getProperty("matcher.sigma",
                    Double.toString(matcher.getSigma()))));
            TTL = Integer.parseInt(properties.getProperty("tracker.state.ttl", "60"));
            int port = Integer.parseInt(properties.getProperty("tracker.port", "1235"));
            memory = new TemporaryMemory<State>(new Factory<State>() {
                @Override
                public State newInstance(String id) {
                    return new State(id);
                }
            }, new StatePublisher(port));

            logger.info("tracker.state.ttl={}", TTL);
            logger.info("tracker.port={}", port);
            int matcherThreads =
                    Integer.parseInt(properties.getProperty("matcher.threads",
                            Integer.toString(Runtime.getRuntime().availableProcessors())));

            StaticScheduler.reset(matcherThreads, (long) 1E4);

            logger.info("matcher.radius.max={}", matcher.getMaxRadius());
            logger.info("matcher.distance.max={}", matcher.getMaxDistance());
            logger.info("matcher.lambda={}", matcher.getLambda());
            logger.info("matcher.sigma={}", matcher.getSigma());
            logger.info("matcher.threads={}", matcherThreads);
        }

        @Override
        protected ResponseHandler response(String request) {
            return new ResponseHandler(request) {
                @Override
                protected RESULT response(String request, StringBuilder response) {
                    try {
                        JSONObject json = new JSONObject(request);

                        if (!json.optString("id").isEmpty()) {
                            if (!json.optString("time").isEmpty()
                                    && !json.optString("point").isEmpty()) {

                                final MatcherSample sample = new MatcherSample(json);
                                final State state = memory.getLocked(sample.id());

                                if (state.inner.sample() != null) {
                                    if (sample.time() < state.inner.sample().time()) {
                                        state.unlock();
                                        logger.warn("received out of order sample");
                                        return RESULT.ERROR;
                                    }
                                }

                                final AtomicReference<Set<MatcherCandidate>> vector =
                                        new AtomicReference<Set<MatcherCandidate>>();
                                InlineScheduler scheduler = StaticScheduler.scheduler();
                                scheduler.spawn(new Task() {
                                    @Override
                                    public void run() {
                                        Stopwatch sw = new Stopwatch();
                                        sw.start();
                                        vector.set(matcher.execute(state.inner.vector(),
                                                state.inner.sample(), sample));
                                        sw.stop();
                                        logger.info("state update of object {} processed in {} ms",
                                                sample.id(), sw.ms());
                                    }
                                });

                                if (!scheduler.sync()) {
                                    state.unlock();
                                    throw new RuntimeException("matcher execution error");
                                } else {
                                    state.inner.update(vector.get(), sample);
                                    state.updateAndUnlock(TTL);
                                    return RESULT.SUCCESS;
                                }
                            } else {
                                String id = json.getString("id");
                                logger.info("received state request for object {}", id);

                                State state = memory.getIfExistsLocked(id);

                                if (state != null) {
                                    response.append(state.inner.toJSON().toString());
                                    state.unlock();
                                } else {
                                    JSONObject empty = new JSONObject();
                                    empty.put("id", id);
                                    response.append(empty.toString());
                                }

                                return RESULT.SUCCESS;
                            }
                        } else if (json.optJSONArray("roads") != null) {
                            logger.debug("received road data request");

                            return RESULT.SUCCESS;
                        } else {
                            throw new RuntimeException("JSON request faulty or incomplete: "
                                    + request);
                        }
                    } catch (Exception e) {
                        logger.error("{}", e.getMessage());
                        e.printStackTrace();
                        return RESULT.ERROR;
                    }
                }
            };
        }
    }

    private static class State extends TemporaryElement<State> {
        final MatcherKState inner = new MatcherKState();

        public State(String id) {
            super(id);
        }
    };

    private static class StatePublisher extends Publisher<State> {
        ZMQ.Context context = null;
        ZMQ.Socket socket = null;

        public StatePublisher(int port) {
            context = ZMQ.context(1);
            socket = context.socket(ZMQ.PUB);
            socket.bind("tcp://*:" + port);
        }

        @Override
        public void publish(String id, State state) {
            try {
                JSONObject json = state.inner.toMonitorJSON();
                json.put("id", id);
                socket.send(json.toString());
            } catch (JSONException e) {
                logger.error("update failed: {}", e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void delete(String id, long time) {
            try {
                JSONObject json = new JSONObject();
                json.put("id", id);
                json.put("time", time);
                socket.send(json.toString());
                logger.info("delete object {}", id);
            } catch (JSONException e) {
                logger.error("delete failed: {}", e.getMessage());
                e.printStackTrace();
            }
        }
    }
}