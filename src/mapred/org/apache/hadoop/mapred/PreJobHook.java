/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred;

import java.util.List;

import org.apache.hadoop.fs.Path;

/**
 * PreJobHook is a hook which is called before job submitted.
 */
public interface PreJobHook {
  public enum JobFileType {
    JOB_JAR, LIB_JAR, TMP_FILE, TMP_ARCHIVE
  }
  
  public static class JobFileMD5 {
    JobFileType type;
    Path file;
    String md5;
    
    JobFileMD5(JobFileType type, Path file, String md5) {
      this.type = type;
      this.file = file;
      this.md5 = md5;
    }
    
    public JobFileType getType() {
      return type;
    }
    
    public Path getFile() {
      return file;
    }
    
    public String getMd5() {
      return md5;
    }
  }
  
  /**
   * Run the Pre-Job Hook
   * 
   * @param jobId JobID of current job
   * @param job The current configuration of the job
   * @param md5s A list of md5 strings for all the files of current job
   */
  public void run(JobID jobId, JobConf job, List<JobFileMD5> md5s) throws Exception;
}
