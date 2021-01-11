/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.front50.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.netflix.spinnaker.kork.web.exceptions.ValidationException;
import com.netflix.spinnaker.orca.api.pipeline.models.PipelineExecution;
import com.netflix.spinnaker.orca.front50.Front50Service;
import com.netflix.spinnaker.orca.pipeline.PipelineValidator;
import java.io.IOException;
import java.util.Optional;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpaPipelineValidator implements PipelineValidator {

  private final Logger log = LoggerFactory.getLogger(getClass());

  private final Front50Service front50Service;
  private final Gson gson = new Gson();

  /* OPA spits JSON */
  private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
  private final OkHttpClient opaClient = new OkHttpClient();

  @Value("${policy.opa.url:http://localhost:8181}")
  private String opaUrl;

  @Value("${policy.opa.policyLocation:/v1/staticPolicy/eval}")
  private String opaPolicyLocation;

  @Value("${policy.opa.enabled:false}")
  private boolean isOpaEnabled;

  @Value("${policy.opa.resultKey:deny}")
  private String opaResultKey;

  @Autowired
  public OpaPipelineValidator(Optional<Front50Service> front50Service) {
    this.front50Service = front50Service.orElse(null);
  }

  @Override
  public void checkRunnable(PipelineExecution pipeline) {
    if (front50Service == null) {
      throw new UnsupportedOperationException(
          "Front50 not enabled, no way to validate pipeline. Fix this by setting front50.enabled: true");
    }
    if (!isOpaEnabled) {
      return;
    }
    try {
      // Form input to opa
      String finalInput = null;
      finalInput = getOpaInput(pipeline);
      Response httpResponse;
      /* build our request to OPA */
      RequestBody requestBody = RequestBody.create(JSON, finalInput);
      String opaFinalUrl = String.format("%s/%s", this.opaUrl, this.opaPolicyLocation);
      log.info(" opaFinalUrl : " + opaFinalUrl);
      String opaStringResponse;
      /* fetch the response from the spawned call execution */
      httpResponse = doPost(opaFinalUrl, requestBody);
      opaStringResponse = httpResponse.body().string();
      log.info("OPA response: {}", opaStringResponse);
      if (isOpaEnabled) {
        if (httpResponse.code() != 200) {
          throw new ValidationException(opaStringResponse, null);
        } else {
          JsonObject opaResponse = gson.fromJson(opaStringResponse, JsonObject.class);
          JsonObject opaResult;
          if (opaResponse.has("result")) {
            opaResult = opaResponse.get("result").getAsJsonObject();
            if (opaResult.has(opaResultKey)) {
              JsonArray resultKey = opaResult.get(opaResultKey).getAsJsonArray();
              if (resultKey.size() != 0) {
                throw new ValidationException(resultKey.get(0).getAsString(), null);
              }
            } else {
              throw new ValidationException(
                  "There is no '" + opaResultKey + "' field in the OPA response", null);
            }
          } else {
            throw new ValidationException("There is no 'result' field in the OPA response", null);
          }
        }
      }
    } catch (IOException e) {
      log.error("Communication exception for OPA at {}: {}", e.toString());
      throw new ValidationException(e.toString(), null);
    }
  }

  private String getOpaInput(PipelineExecution pipeline) {

    String finalInput = null;
    JsonObject newPipeline = pipelineToJsonObject(pipeline);
    finalInput = gson.toJson(addWrapper(newPipeline, "input"));
    return finalInput;
  }

  private JsonObject addWrapper(JsonObject pipeline, String wrapper) {

    JsonObject input = new JsonObject();
    input.add(wrapper, pipeline);
    return input;
  }

  private JsonObject pipelineToJsonObject(PipelineExecution pipeline) {

    String pipelineStr = gson.toJson(pipeline, PipelineExecution.class);
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      pipelineStr = objectMapper.writeValueAsString(pipeline);
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }
    return gson.fromJson(pipelineStr, JsonObject.class);
  }

  private Response doGet(String url) throws IOException {

    Request req = (new Request.Builder()).url(url).get().build();
    return getResponse(url, req);
  }

  private Response doPost(String url, RequestBody requestBody) throws IOException {

    Request req = (new Request.Builder()).url(url).post(requestBody).build();
    return getResponse(url, req);
  }

  private Response getResponse(String url, Request req) throws IOException {

    Response httpResponse = this.opaClient.newCall(req).execute();
    ResponseBody responseBody = httpResponse.body();

    if (responseBody == null) {
      throw new IOException("Http call yielded null response!! url:" + url);
    }
    return httpResponse;
  }
}
