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
package org.apache.pinot.tools;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pinot.common.auth.AuthProviderUtils;
import org.apache.pinot.common.auth.BasicAuthUtils;
import org.apache.pinot.spi.auth.AuthProvider;
import org.apache.pinot.spi.plugin.PluginManager;
import org.apache.pinot.tools.utils.AuthUtils;


/**
 * Quick start for Time Series Engine with authentication.
 *
 * To test this quick start, you can run the following command in pinot-tools/src/main/resources/scripts/timeseries/
 * python3 run_ts_query.py --auth
 */
public class TimeSeriesEngineAuthQuickStart extends TimeSeriesEngineQuickStart {

  @Override
  public List<String> types() {
    return Collections.singletonList("TIME_SERIES_AUTH");
  }

  @Override
  public AuthProvider getAuthProvider() {
    return AuthProviderUtils.makeAuthProvider(BasicAuthUtils.toBasicAuthToken("admin", "verysecret"));
  }

  @Override
  public Map<String, Object> getConfigOverrides() {
    Map<String, Object> properties = new HashMap<>(super.getConfigOverrides());
    properties.putAll(AuthUtils.getAuthQuickStartDefaultConfigs());
    return properties;
  }

  public static void main(String[] args) throws Exception {
    PluginManager.get().init();
    new TimeSeriesEngineAuthQuickStart().execute();
    printStatus(Color.GREEN, "Default login credential to login controller is admin/verysecret.");
  }
}
