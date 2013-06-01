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

import java.io.IOException;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.MapFile;

/** An {@link InputFormat} for {@link SequenceFile}s. */
public class SequenceFileInputFormat<K, V> extends FileInputFormat<K, V> {

    public SequenceFileInputFormat() {
        setMinSplitSize(SequenceFile.SYNC_INTERVAL);
    }

    @Override
    protected LocatedFileStatus[] listLocatedStatus(JobConf job) throws IOException {
        LocatedFileStatus[] files = super.listLocatedStatus(job);
        for (int i = 0; i < files.length; i++) {
            LocatedFileStatus file = files[i];
            if (file.isDir()) { // it's a MapFile
                Path dataFile = new Path(file.getPath(), MapFile.DATA_FILE_NAME);
                FileSystem fs = file.getPath().getFileSystem(job);
                // use the data file
                files[i] = fs.listLocatedStatus(dataFile)[0];
            }
        }
        return files;
    }

    public RecordReader<K, V> getRecordReader(InputSplit split, JobConf job, Reporter reporter) throws IOException {

        reporter.setStatus(split.toString());

        return new SequenceFileRecordReader<K, V>(job, (FileSplit) split);
    }

}
