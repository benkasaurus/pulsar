/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.api.examples;

import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.apache.pulsar.functions.api.Context;
import org.apache.pulsar.functions.api.Function;

import java.util.HashMap;
import java.util.Map;

/**
 * Example function that uses the built in publish function in the context
 * to publish to a desired topic based on config and setting various message configurations to be passed along.
 *
 */
public class PublishFunctionWithMessageConf implements Function<String, Void> {
    @Override
    public Void process(String input, Context context) {
        String publishTopic = (String) context.getUserConfigValueOrDefault("publish-topic", "publishtopic");
        String output = String.format("%s!", input);

        Map<String, String> properties = new HashMap<>();
        properties.put("input_topic", context.getCurrentRecord().getTopicName().get());
        properties.putAll(context.getCurrentRecord().getProperties());

        Map<String, Object> messageConf = new HashMap<>();
        messageConf.put(TypedMessageBuilder.CONF_PROPERTIES, properties);
        if (context.getCurrentRecord().getKey().isPresent()) {
            messageConf.put(TypedMessageBuilder.CONF_KEY, context.getCurrentRecord().getKey().get());
        }
        messageConf.put(TypedMessageBuilder.CONF_EVENT_TIME, System.currentTimeMillis());
        context.publish(publishTopic, output, null, messageConf);
        return null;
    }
}
