/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.bigquery.storage.v1beta2.it;

/** Test helper class to generate BigQuery resource paths. */
public class BigQueryResource {

  /**
   * Returns a BigQuery table resource path from the provided parameters into the following format:
   * projects/{projectId}/datasets/{datasetId}/tables/{tableId}
   *
   * @param projectId
   * @param datasetId
   * @param tableId
   * @return a path to a table resource.
   */
  public static String FormatTableResource(String projectId, String datasetId, String tableId) {
    return String.format("projects/%s/datasets/%s/tables/%s", projectId, datasetId, tableId);
  }
}
