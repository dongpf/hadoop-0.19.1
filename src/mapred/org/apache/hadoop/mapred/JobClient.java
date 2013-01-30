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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import javax.security.auth.login.LoginException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.mapred.Counters.Counter;
import org.apache.hadoop.mapred.Counters.Group;
import org.apache.hadoop.mapred.PreJobHook.JobFileMD5;
import org.apache.hadoop.mapred.PreJobHook.JobFileType;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * <code>JobClient</code> is the primary interface for the user-job to interact
 * with the {@link JobTracker}.
 * 
 * <code>JobClient</code> provides facilities to submit jobs, track their 
 * progress, access component-tasks' reports/logs, get the Map-Reduce cluster
 * status information etc.
 * 
 * <p>The job submission process involves:
 * <ol>
 *   <li>
 *   Checking the input and output specifications of the job.
 *   </li>
 *   <li>
 *   Computing the {@link InputSplit}s for the job.
 *   </li>
 *   <li>
 *   Setup the requisite accounting information for the {@link DistributedCache} 
 *   of the job, if necessary.
 *   </li>
 *   <li>
 *   Copying the job's jar and configuration to the map-reduce system directory 
 *   on the distributed file-system. 
 *   </li>
 *   <li>
 *   Submitting the job to the <code>JobTracker</code> and optionally monitoring
 *   it's status.
 *   </li>
 * </ol></p>
 *  
 * Normally the user creates the application, describes various facets of the
 * job via {@link JobConf} and then uses the <code>JobClient</code> to submit 
 * the job and monitor its progress.
 * 
 * <p>Here is an example on how to use <code>JobClient</code>:</p>
 * <p><blockquote><pre>
 *     // Create a new JobConf
 *     JobConf job = new JobConf(new Configuration(), MyJob.class);
 *     
 *     // Specify various job-specific parameters     
 *     job.setJobName("myjob");
 *     
 *     job.setInputPath(new Path("in"));
 *     job.setOutputPath(new Path("out"));
 *     
 *     job.setMapperClass(MyJob.MyMapper.class);
 *     job.setReducerClass(MyJob.MyReducer.class);
 *
 *     // Submit the job, then poll for progress until the job is complete
 *     JobClient.runJob(job);
 * </pre></blockquote></p>
 * 
 * <h4 id="JobControl">Job Control</h4>
 * 
 * <p>At times clients would chain map-reduce jobs to accomplish complex tasks 
 * which cannot be done via a single map-reduce job. This is fairly easy since 
 * the output of the job, typically, goes to distributed file-system and that 
 * can be used as the input for the next job.</p>
 * 
 * <p>However, this also means that the onus on ensuring jobs are complete 
 * (success/failure) lies squarely on the clients. In such situations the 
 * various job-control options are:
 * <ol>
 *   <li>
 *   {@link #runJob(JobConf)} : submits the job and returns only after 
 *   the job has completed.
 *   </li>
 *   <li>
 *   {@link #submitJob(JobConf)} : only submits the job, then poll the 
 *   returned handle to the {@link RunningJob} to query status and make 
 *   scheduling decisions.
 *   </li>
 *   <li>
 *   {@link JobConf#setJobEndNotificationURI(String)} : setup a notification
 *   on job-completion, thus avoiding polling.
 *   </li>
 * </ol></p>
 * 
 * @see JobConf
 * @see ClusterStatus
 * @see Tool
 * @see DistributedCache
 */
public class JobClient extends Configured implements MRConstants, Tool  {
  private static final Log LOG = LogFactory.getLog(JobClient.class);
  public static enum TaskStatusFilter { NONE, KILLED, FAILED, SUCCEEDED, ALL }
  private TaskStatusFilter taskOutputFilter = TaskStatusFilter.FAILED; 
  private static Configuration commandLineConfig;
  static long MAX_JOBPROFILE_AGE = 1000 * 2;
  private static final String TASKLOG_PULL_TIMEOUT_KEY = 
          "mapreduce.client.tasklog.timeout";
  private static final int DEFAULT_TASKLOG_TIMEOUT = 60000;
  static int tasklogtimeout;

  private static final String PROFILING_FILE_DOWNLOAD_ENABLED = 
          "mapreduce.client.profile.download.enabled";
  private static final String PROFILING_FILE_DOWNLOAD_DIR = 
          "mapreduce.client.profile.download.dir";

  /**
   * A NetworkedJob is an implementation of RunningJob.  It holds
   * a JobProfile object to provide some info, and interacts with the
   * remote service to provide certain functionality.
   */
  class NetworkedJob implements RunningJob {
    JobProfile profile;
    JobStatus status;
    long statustime;

    /**
     * We store a JobProfile and a timestamp for when we last
     * acquired the job profile.  If the job is null, then we cannot
     * perform any of the tasks.  The job might be null if the JobTracker
     * has completely forgotten about the job.  (eg, 24 hours after the
     * job completes.)
     */
    public NetworkedJob(JobStatus job) throws IOException {
      this.status = job;
      this.profile = jobSubmitClient.getJobProfile(job.getJobID());
      this.statustime = System.currentTimeMillis();
    }

    /**
     * Some methods rely on having a recent job profile object.  Refresh
     * it, if necessary
     */
    synchronized void ensureFreshStatus() throws IOException {
      if (System.currentTimeMillis() - statustime > MAX_JOBPROFILE_AGE) {
        updateStatus();
      }
    }
    
    /** Some methods need to update status immediately. So, refresh
     * immediately
     * @throws IOException
     */
    synchronized void updateStatus() throws IOException {
      this.status = jobSubmitClient.getJobStatus(profile.getJobID());
      this.statustime = System.currentTimeMillis();
    }

    /**
     * An identifier for the job
     */
    public JobID getID() {
      return profile.getJobID();
    }
    
    /** @deprecated This method is deprecated and will be removed. Applications should 
     * rather use {@link #getID()}.*/
    @Deprecated
    public String getJobID() {
      return profile.getJobID().toString();
    }
    
    /**
     * The user-specified job name
     */
    public String getJobName() {
      return profile.getJobName();
    }

    /**
     * The name of the job file
     */
    public String getJobFile() {
      return profile.getJobFile();
    }

    /**
     * A URL where the job's status can be seen
     */
    public String getTrackingURL() {
      return profile.getURL().toString();
    }

    /**
     * A float between 0.0 and 1.0, indicating the % of map work
     * completed.
     */
    public float mapProgress() throws IOException {
      ensureFreshStatus();
      return status.mapProgress();
    }

    /**
     * A float between 0.0 and 1.0, indicating the % of reduce work
     * completed.
     */
    public float reduceProgress() throws IOException {
      ensureFreshStatus();
      return status.reduceProgress();
    }

    /**
     * A float between 0.0 and 1.0, indicating the % of cleanup work
     * completed.
     */
    public float cleanupProgress() throws IOException {
      ensureFreshStatus();
      return status.cleanupProgress();
    }

    /**
     * A float between 0.0 and 1.0, indicating the % of setup work
     * completed.
     */
    public float setupProgress() throws IOException {
      ensureFreshStatus();
      return status.setupProgress();
    }

    /**
     * Returns immediately whether the whole job is done yet or not.
     */
    public synchronized boolean isComplete() throws IOException {
      updateStatus();
      return (status.getRunState() == JobStatus.SUCCEEDED ||
              status.getRunState() == JobStatus.FAILED ||
              status.getRunState() == JobStatus.KILLED);
    }

    /**
     * True iff job completed successfully.
     */
    public synchronized boolean isSuccessful() throws IOException {
      updateStatus();
      return status.getRunState() == JobStatus.SUCCEEDED;
    }

    /**
     * Blocks until the job is finished
     */
    public void waitForCompletion() throws IOException {
      while (!isComplete()) {
        try {
          Thread.sleep(5000);
        } catch (InterruptedException ie) {
        }
      }
    }

    /**
     * Tells the service to get the state of the current job.
     */
    public synchronized int getJobState() throws IOException {
      updateStatus();
      return status.getRunState();
    }
    
    /**
     * Tells the service to terminate the current job.
     */
    public synchronized void killJob() throws IOException {
      jobSubmitClient.killJob(getID());
    }
   
    
    /** Set the priority of the job.
    * @param priority new priority of the job. 
    */
    public synchronized void setJobPriority(String priority) 
                                                throws IOException {
      jobSubmitClient.setJobPriority(getID(), priority);
    }
    
    /**
     * Kill indicated task attempt.
     * @param taskId the id of the task to kill.
     * @param shouldFail if true the task is failed and added to failed tasks list, otherwise
     * it is just killed, w/o affecting job failure status.
     */
    public synchronized void killTask(TaskAttemptID taskId, boolean shouldFail) throws IOException {
      jobSubmitClient.killTask(taskId, shouldFail);
    }

    /** @deprecated Applications should rather use {@link #killTask(TaskAttemptID, boolean)}*/
    @Deprecated
    public synchronized void killTask(String taskId, boolean shouldFail) throws IOException {
      killTask(TaskAttemptID.forName(taskId), shouldFail);
    }
    
    /**
     * Fetch task completion events from jobtracker for this job. 
     */
    public synchronized TaskCompletionEvent[] getTaskCompletionEvents(
                                                                      int startFrom) throws IOException{
      return jobSubmitClient.getTaskCompletionEvents(
                                                     getID(), startFrom, 10); 
    }

    /**
     * Dump stats to screen
     */
    @Override
    public String toString() {
      try {
        updateStatus();
      } catch (IOException e) {
      }
      return "Job: " + profile.getJobID() + "\n" + 
        "file: " + profile.getJobFile() + "\n" + 
        "tracking URL: " + profile.getURL() + "\n" + 
        "map() completion: " + status.mapProgress() + "\n" + 
        "reduce() completion: " + status.reduceProgress();
    }
        
    /**
     * Returns the counters for this job
     */
    public Counters getCounters() throws IOException {
      return jobSubmitClient.getJobCounters(getID());
    }
  }

  JobSubmissionProtocol jobSubmitClient;
  Path sysDir = null;
  
  FileSystem fs = null;

  static Random r = new Random();

  /**
   * Create a job client.
   */
  public JobClient() {
  }
    
  /**
   * Build a job client with the given {@link JobConf}, and connect to the 
   * default {@link JobTracker}.
   * 
   * @param conf the job configuration.
   * @throws IOException
   */
  public JobClient(JobConf conf) throws IOException {
    setConf(conf);
    init(conf);
  }

  /**
   * set the command line config in the jobclient. these are
   * parameters paassed from the command line and stored in 
   * conf
   * @param conf the configuration object to set.
   */
  static synchronized void  setCommandLineConfig(Configuration conf) {
    commandLineConfig = conf;
  }
  
  /**
   * return the command line configuration
   */
  public static synchronized Configuration getCommandLineConfig() {
    return commandLineConfig;
  }
  
 
  /**
   * Connect to the default {@link JobTracker}.
   * @param conf the job configuration.
   * @throws IOException
   */
  public void init(JobConf conf) throws IOException {
    String tracker = conf.get("mapred.job.tracker", "local");
    tasklogtimeout = conf.getInt(
        TASKLOG_PULL_TIMEOUT_KEY, DEFAULT_TASKLOG_TIMEOUT);
    if ("local".equals(tracker)) {
      this.jobSubmitClient = new LocalJobRunner(conf);
    } else {
      this.jobSubmitClient = createRPCProxy(JobTracker.getAddress(conf), conf);
    }        
  }

  private JobSubmissionProtocol createRPCProxy(InetSocketAddress addr,
      Configuration conf) throws IOException {
    return (JobSubmissionProtocol) RPC.getProxy(JobSubmissionProtocol.class,
        JobSubmissionProtocol.versionID, addr, getUGI(conf), conf,
        NetUtils.getSocketFactory(conf, JobSubmissionProtocol.class));
  }

  /**
   * Build a job client, connect to the indicated job tracker.
   * 
   * @param jobTrackAddr the job tracker to connect to.
   * @param conf configuration.
   */
  public JobClient(InetSocketAddress jobTrackAddr, 
                   Configuration conf) throws IOException {
    jobSubmitClient = createRPCProxy(jobTrackAddr, conf);
  }

  /**
   * Close the <code>JobClient</code>.
   */
  public synchronized void close() throws IOException {
    if (!(jobSubmitClient instanceof LocalJobRunner)) {
      RPC.stopProxy(jobSubmitClient);
    }
  }

  /**
   * Get a filesystem handle.  We need this to prepare jobs
   * for submission to the MapReduce system.
   * 
   * @return the filesystem handle.
   */
  public synchronized FileSystem getFs() throws IOException {
    if (this.fs == null) {
      Path sysDir = getSystemDir();
      this.fs = sysDir.getFileSystem(getConf());
    }
    return fs;
  }
  
  /* see if two file systems are the same or not
   *
   */
  private boolean compareFs(FileSystem srcFs, FileSystem destFs) {
    URI srcUri = srcFs.getUri();
    URI dstUri = destFs.getUri();
    if (srcUri.getScheme() == null) {
      return false;
    }
    if (!srcUri.getScheme().equals(dstUri.getScheme())) {
      return false;
    }
    String srcHost = srcUri.getHost();    
    String dstHost = dstUri.getHost();
    if ((srcHost != null) && (dstHost != null)) {
      try {
        srcHost = InetAddress.getByName(srcHost).getCanonicalHostName();
        dstHost = InetAddress.getByName(dstHost).getCanonicalHostName();
      } catch(UnknownHostException ue) {
        return false;
      }
      if (!srcHost.equals(dstHost)) {
        return false;
      }
    }
    else if (srcHost == null && dstHost != null) {
      return false;
    }
    else if (srcHost != null && dstHost == null) {
      return false;
    }
    //check for ports
    if (srcUri.getPort() != dstUri.getPort()) {
      return false;
    }
    return true;
  }

  /**
   * 
   * copies a file to the jobtracker filesystem and returns the path where it was copied to
   * @param parentDir Parent directory of copy destination
   * @param originalPath
   * @param job
   * @param replication
   * @param sharedCacheDir
   * @param needMd5 
   *    True if md5 result shall be stored (to md5s)
   * @param type
   * @param md5s
   * @return
   * @throws IOException
   */
  private Path copyRemoteFiles(Path parentDir, Path originalPath,
              JobConf job, short replication, Path sharedCacheDir, 
              boolean needMd5, JobFileType type, List<JobFileMD5> md5s) throws IOException {
    FileSystem remoteFs = getFs();
    FileSystem originalFs = originalPath.getFileSystem(job);
    if (compareFs(originalFs, remoteFs)) {
      return originalPath;
    }

    if (originalFs.isFile(originalPath) && sharedCacheDir != null) {
      String md5String = MD5Hash.digest(new FileInputStream(originalPath.toUri().getPath()))
          .toString();
      if (needMd5) {
        md5s.add(new JobFileMD5(type, originalPath, md5String));
      }

      if (filesInCache == null)
        populateFileListings(remoteFs, sharedCacheDir);
      // find this file in cache first
      Path cachedFile = new Path(sharedCacheDir, md5String + "_" + originalPath.getName());
      if (filesInCache.containsKey(cachedFile.makeQualified(remoteFs))) {
        LOG.debug("Found " + originalPath + " -> " + cachedFile.makeQualified(remoteFs));
        return cachedFile;
      }
      // upload this file when fail to find it in the cache
      Path tmpCachedFile;
      // In most case, this loop only do one time
      do {
        tmpCachedFile = new Path(sharedCacheDir, "tmp_" + originalPath.getName() + "_"
            + r.nextLong());
      } while (remoteFs.exists(tmpCachedFile));
      FileUtil.copy(originalFs, originalPath, remoteFs, tmpCachedFile, false, job);
      remoteFs.setReplication(tmpCachedFile, replication);
      remoteFs.setPermission(tmpCachedFile, new FsPermission(JOB_DIR_PERMISSION));
      if (!remoteFs.rename(tmpCachedFile, cachedFile)) {
        LOG.debug("Fail to rename uploaded temp file, delete it: "
                  + tmpCachedFile.makeQualified(remoteFs));
        try {
          if (!remoteFs.delete(tmpCachedFile, true)) {
            throw new IOException();
          }
        } catch (IOException e) {
          // ignore this exception
          LOG.debug("Fail to delete temp uploaded file");
        }
        // if there are multiple clients racing to upload the new jar - only
        // one of them will succeed. Check if we failed because the file already
        // exists. if so, ignore and move on
        if (!remoteFs.exists(cachedFile)) {
          throw new IOException("Unable to upload or find shared file: "
                  + originalPath.toString());
        }
        LOG.debug("Found file has been uploaded by other user at the same time");
      } else {
        LOG.debug("Upload " + originalPath + " -> " + cachedFile.makeQualified(remoteFs));
      }
      filesInCache.put(cachedFile.makeQualified(remoteFs), remoteFs.getFileStatus(cachedFile));

      return cachedFile;
    }

    // this might have name collisions. copy will throw an exception
    // parse the original path to create new path
    Path newPath = new Path(parentDir, originalPath.getName());
    FileUtil.copy(originalFs, originalPath, remoteFs, newPath, false, job);
    LOG.debug("Upload " + originalPath + " -> " + newPath.makeQualified(remoteFs));
    remoteFs.setReplication(newPath, replication);
    return newPath;
  }
  
  //files cached in HDFS
  private HashMap<Path, FileStatus> filesInCache = null; 
  private long filesInCacheTimeStamp = 0;
  private final static long FCACHE_REFRESH_INTERVAL = 1000L * 60 * 60;

  private void populateFileListings(FileSystem fs, Path f) {
    if (filesInCache == null) filesInCache = new HashMap<Path, FileStatus>(); 

    long now = System.currentTimeMillis();
    if (now - filesInCacheTimeStamp < FCACHE_REFRESH_INTERVAL) {
      // the list of cached files has been refreshed recently.
      return;
    }

    localizeFileListings(fs, f);
    filesInCacheTimeStamp = now;
  }

  private void localizeFileListings(FileSystem fs, Path f) {
    FileStatus[] lstatus;
    try {
      lstatus = fs.listStatus(f);
      for (int i = 0; i < lstatus.length; i++) {
        if (!lstatus[i].isDir() && !lstatus[i].getPath().getName().startsWith("tmp_")) {
          filesInCache.put(lstatus[i].getPath(), lstatus[i]);
        }
      }
    } catch (Exception e) {
      // If something goes wrong, the worst that can happen is that files don't
      // get cached. Noting fatal.
    }
  }  
  
 
  /**
   * configure the jobconf of the user with the command line options of 
   * -libjars, -files, -archives
   * @param conf
   * @throws IOException
   */
  private List<JobFileMD5> configureCommandLineOptions(JobConf job, Path submitJobDir, boolean enabledPreHook) 
    throws IOException {
    
    final String warning = "Use genericOptions for the option ";

    if (!(job.getBoolean("mapred.used.genericoptionsparser", false))) {
      LOG.warn("Use GenericOptionsParser for parsing the arguments. " +
               "Applications should implement Tool for the same.");
    }

    // get all the command line arguments into the 
    // jobconf passed in by the user conf
    Configuration commandConf = JobClient.getCommandLineConfig();
    String files = null;
    String libjars = null;
    String archives = null;

    files = job.get("tmpfiles");
    if (files == null) {
      if (commandConf != null) {
        files = commandConf.get("tmpfiles");
        if (files != null) {
          LOG.warn(warning + "-files");
        }
      }
    }

    libjars = job.get("tmpjars");
    if (libjars == null) {
      if (commandConf != null) {
        libjars = commandConf.get("tmpjars");
        if (libjars != null) {
          LOG.warn(warning + "-libjars");
        }
      }
    }

    archives = job.get("tmparchives");
    if (archives == null) {
      if (commandConf != null) {
        archives = commandConf.get("tmparchives");
        if (archives != null) {
          LOG.warn(warning + "-archives");
        }
      }
    }

    // A collection of files for determining which jar/files/archives should skip 
    // computing their md5. 
    // job.jar is always not in this collection.
    Collection<String> skips = job.getStringCollection("mapred.client.pre.hooks.skips");
    Collection<Path> preHookSkips = new ArrayList<Path>(skips.size());
    for(String one : skips) {
      preHookSkips.add(new Path(one));
    }

    /*
     * set this user's id in job configuration, so later job files can be
     * accessed using this user's id
     */
    UnixUserGroupInformation ugi = getUGI(job);
      
    //
    // Figure out what fs the JobTracker is using.  Copy the
    // job to it, under a temporary name.  This allows DFS to work,
    // and under the local fs also provides UNIX-like object loading 
    // semantics.  (that is, if the job file is deleted right after
    // submission, we can still run the submission to completion)
    //

    // Create a number of filenames in the JobTracker's fs namespace
    FileSystem fs = getFs();
    LOG.debug("default FileSystem: " + fs.getUri());
    fs.delete(submitJobDir, true);
    submitJobDir = new Path(fs.makeQualified(submitJobDir).toUri().getPath());
    LOG.debug("job submit path is: " + submitJobDir);
    FsPermission mapredSysPerms = new FsPermission(JOB_DIR_PERMISSION);
    FileSystem.mkdirs(fs, submitJobDir, mapredSysPerms);
    Path filesDir = new Path(submitJobDir, "files");
    Path archivesDir = new Path(submitJobDir, "archives");
    Path libjarsDir = new Path(submitJobDir, "libjars");
    short replication = (short)job.getInt("mapred.submit.replication", 10);

    //shared cache issues
    boolean shared = job.getBoolean("mapred.cache.shared.enabled", false);
    Path sharedCacheDir = shared ? new Path(submitJobDir.getParent(), "shared_cache") : null;
    LOG.debug("shared cache path is: " + sharedCacheDir);
    //create cache dir with 777 permission
    if (shared && !fs.exists(sharedCacheDir)) FileSystem.mkdirs(fs, sharedCacheDir, mapredSysPerms);

    // add jars and all the command line files, libjars and archive
    // first copy them to jobtrackers filesystem 
    
    List<JobFileMD5> md5s = new ArrayList<JobFileMD5>();
    String originalJar = job.getJar();
    if (originalJar != null) {
      Path originalJarPath = new Path(originalJar).makeQualified(FileSystem.getLocal(job));
      if (!fs.exists(libjarsDir)) FileSystem.mkdirs(fs, libjarsDir, mapredSysPerms);
      //upload the jar file
      Path submitJarFile = null;
      if (shared) {
        submitJarFile = copyRemoteFiles(libjarsDir, originalJarPath, job, replication, sharedCacheDir, enabledPreHook, JobFileType.JOB_JAR, md5s);
        // when file system of jobClient and file system of jobTracker are same, not use shared cache
        FileStatus fileStatus = null;
        if (filesInCache != null) fileStatus = filesInCache.get(submitJarFile.makeQualified(fs));
        if (fileStatus == null) {
          FileSystem fileSystem = FileSystem.get(submitJarFile.toUri(), job);
          fileStatus = fileSystem.getFileStatus(submitJarFile);
        }
        job.setLong("mapred.jar.timestamp", fileStatus.getModificationTime());
        job.setLong("mapred.jar.length", fileStatus.getLen());
      } else {
        submitJarFile = new Path(submitJobDir, "job.jar");
        fs.copyFromLocalFile(originalJarPath, submitJarFile);
        fs.setPermission(submitJarFile, new FsPermission(JOB_FILE_PERMISSION));
      }
      job.setJar(submitJarFile.toString());
      // use jar name if job is not named. 
      if ("".equals(job.getJobName())) job.setJobName(originalJarPath.getName());
    } else {
      LOG.warn("No job jar file set. User classes may not be found. See JobConf(Class) or JobConf#setJar(String).");
    }
    
    if (files != null) {
      if (!fs.exists(filesDir)) FileSystem.mkdirs(fs, filesDir, mapredSysPerms);
      String[] fileArr = files.split(",");
      for (String tmpFile: fileArr) {
        Path tmpFilePath = new Path(tmpFile);
        boolean needMd5 = preHookSkips.contains(tmpFilePath) ? false : enabledPreHook;
        Path newPath = copyRemoteFiles(filesDir, tmpFilePath, job, replication, sharedCacheDir, needMd5, JobFileType.TMP_FILE, md5s);
        try {
          URI pathURI = new URI(newPath.toUri().toString() + "#" + tmpFilePath.getName());
          DistributedCache.addCacheFile(pathURI, job);
        } catch(URISyntaxException ue) {
          //should not throw a uri exception 
          throw new IOException("Failed to create uri for " + tmpFile);
        }
        DistributedCache.createSymlink(job);
      }
    }
    
    if (libjars != null) {
      if (!fs.exists(libjarsDir)) FileSystem.mkdirs(fs, libjarsDir, mapredSysPerms);
      String[] libjarsArr = libjars.split(",");
      for (String tmpjar: libjarsArr) {
        Path tmpjarPath = new Path(tmpjar);
        boolean needMd5 = preHookSkips.contains(tmpjarPath) ? false : enabledPreHook;
        Path newPath = copyRemoteFiles(libjarsDir, tmpjarPath, job, replication, sharedCacheDir, needMd5, JobFileType.LIB_JAR, md5s);
        DistributedCache.addArchiveToClassPath(newPath, job);
      }
    }
    
    if (archives != null) {
     if (!fs.exists(archivesDir)) FileSystem.mkdirs(fs, archivesDir, mapredSysPerms);
     String[] archivesArr = archives.split(",");
     for (String tmpArchive: archivesArr) {
       Path tmpArchivePath = new Path(tmpArchive);
       boolean needMd5 = preHookSkips.contains(tmpArchivePath) ? false : enabledPreHook;
       Path newPath = copyRemoteFiles(archivesDir, tmpArchivePath, job, replication, sharedCacheDir, needMd5, JobFileType.TMP_ARCHIVE, md5s);
       try {
         URI pathURI = new URI(newPath.toUri().toString() + "#" + tmpArchivePath.getName());
         DistributedCache.addCacheArchive(pathURI, job);
       } catch(URISyntaxException ue) {
         //should not throw an uri excpetion
         throw new IOException("Failed to create uri for " + tmpArchive);
       }
       DistributedCache.createSymlink(job);
     }
    }
    
    //  set the timestamps and length of the archives and files
    URI[] tarchives = DistributedCache.getCacheArchives(job);
    if (tarchives != null) {
      StringBuffer archiveTimestamps = new StringBuffer();
      StringBuffer archiveLength     = new StringBuffer();
      for (int i = 0; i < tarchives.length; i++) {
        if (i != 0) {
          archiveTimestamps.append(',');
          archiveLength.append(',');
        }
        FileStatus fileStatus = null;
        if (filesInCache != null) fileStatus = filesInCache.get(tarchives[i]);
        if (fileStatus == null) {
          FileSystem fileSystem = FileSystem.get(tarchives[i], job);
          fileStatus = fileSystem.getFileStatus(new Path(tarchives[i].getPath()));
        }
        long timeStamp  = fileStatus.getModificationTime();;
        long fileLength = fileStatus.getLen();
        archiveTimestamps.append(timeStamp);
        archiveLength.append(fileLength);
      }

      DistributedCache.setArchiveTimestamps(job, archiveTimestamps.toString());
      DistributedCache.setArchiveLength(job, archiveLength.toString());
    }

    URI[] tfiles = DistributedCache.getCacheFiles(job);
    if (tfiles != null) {
      StringBuffer fileTimestamps = new StringBuffer();
      StringBuffer fileLength     = new StringBuffer();
      for (int i = 0; i < tfiles.length; i++) {
        if (i != 0) {
          fileTimestamps.append(',');
          fileLength.append(',');
        }
        FileStatus fileStatus = null;
        if (filesInCache != null) fileStatus = filesInCache.get(tfiles[i]);
        if (fileStatus == null) {
          FileSystem fileSystem = FileSystem.get(tfiles[i], job);
          fileStatus = fileSystem.getFileStatus(new Path(tfiles[i].getPath()));
        }
        long timeStamp  = fileStatus.getModificationTime();;
        long length = fileStatus.getLen();
        fileTimestamps.append(timeStamp);
        fileLength.append(length);
      }

      DistributedCache.setFileTimestamps(job, fileTimestamps.toString());
      DistributedCache.setFileLength(job, fileLength.toString());
    }

    // Set the user's name and working directory
    job.setUser(ugi.getUserName());
    if (ugi.getGroupNames().length > 0) {
      job.set("group.name", ugi.getGroupNames()[0]);
    }
    if (job.getWorkingDirectory() == null) {
      job.setWorkingDirectory(fs.getWorkingDirectory());          
    }

    return md5s;
  }

  private UnixUserGroupInformation getUGI(Configuration job) throws IOException {
    UnixUserGroupInformation ugi = null;
    try {
      ugi = UnixUserGroupInformation.login(job, true);
    } catch (LoginException e) {
      throw (IOException)(new IOException(
          "Failed to get the current user's information.").initCause(e));
    }
    return ugi;
  }
  
  /**
   * Submit a job to the MR system.
   * 
   * This returns a handle to the {@link RunningJob} which can be used to track
   * the running-job.
   * 
   * @param jobFile the job configuration.
   * @return a handle to the {@link RunningJob} which can be used to track the
   *         running-job.
   * @throws FileNotFoundException
   * @throws InvalidJobConfException
   * @throws IOException
   */
  public RunningJob submitJob(String jobFile) throws FileNotFoundException, 
                                                     InvalidJobConfException, 
                                                     IOException {
    // Load in the submitted job details
    JobConf job = new JobConf(jobFile);
    return submitJob(job);
  }
    
  // job files are world-wide readable and owner writable
  final private static FsPermission JOB_FILE_PERMISSION = 
    FsPermission.createImmutable((short) 0644); // rw-r--r--

  // job submission directory is world readable/writable/executable
  final static FsPermission JOB_DIR_PERMISSION =
    FsPermission.createImmutable((short) 0777); // rwx-rwx-rwx

  /** Normalize the output path defined in job configuration.
   * Store the normalized path back into job configuration.
   * 
   * @param job job configuration
   * @throws IOException if any error occurs during normalization
   */
  private static void normalizeOutputPath(JobConf job) throws IOException {
    Path out = FileOutputFormat.getOutputPath(job);
    if (out!=null) {
      FileOutputFormat.setOutputPath(job, 
          out.getFileSystem(job).makeQualified(out));
    }
  }
  
  /**
   * Submit a job to the MR system.
   * This returns a handle to the {@link RunningJob} which can be used to track
   * the running-job.
   * 
   * @param job the job configuration.
   * @return a handle to the {@link RunningJob} which can be used to track the
   *         running-job.
   * @throws FileNotFoundException
   * @throws InvalidJobConfException
   * @throws IOException
   */
  public RunningJob submitJob(JobConf job) throws FileNotFoundException, 
                                  InvalidJobConfException, IOException {
    /*
     * configure the command line options correctly on the submitting dfs
     */
	  
    JobID jobId = jobSubmitClient.getNewJobId();
    Path submitJobDir = new Path(getSystemDir(), jobId.toString());
    boolean enabledPreHook = job.getBoolean("mapred.client.pre.hook.enabled",
        false);
    List<JobFileMD5> md5s = configureCommandLineOptions(job, submitJobDir,
        enabledPreHook);
    Path submitJobFile = new Path(submitJobDir, "job.xml");
    Path submitSplitFile = new Path(submitJobDir, "job.split");
    
    // Normalize and check the output specification
    normalizeOutputPath(job);
    job.getOutputFormat().checkOutputSpecs(fs, job);

    // Create the splits for the job
    LOG.debug("Creating splits at " + fs.makeQualified(submitSplitFile));
    InputSplit[] splits = 
      job.getInputFormat().getSplits(job, job.getNumMapTasks());
    // sort the splits into order based on size, so that the biggest
    // go first
    Arrays.sort(splits, new Comparator<InputSplit>() {
      public int compare(InputSplit a, InputSplit b) {
        try {
          long left = a.getLength();
          long right = b.getLength();
          if (left == right) {
            return 0;
          } else if (left < right) {
            return 1;
          } else {
            return -1;
          }
        } catch (IOException ie) {
          throw new RuntimeException("Problem getting input split size",
                                     ie);
        }
      }
    });
    // write the splits to a file for the job tracker
    FSDataOutputStream out = FileSystem.create(fs,
        submitSplitFile, new FsPermission(JOB_FILE_PERMISSION));
    try {
      writeSplitsFile(splits, out);
    } finally {
      out.close();
    }
    job.set("mapred.job.split.file", submitSplitFile.toString());
    job.setNumMapTasks(splits.length);
    
    // added by tuhai 20101227 mapred.map.max
    int nMaxNumMaps = -1;
    int numMaps = job.getNumMapTasks();
    String maxNumMaps = job.get("mapred.map.max");
    if(maxNumMaps != null)    {
	    try {
	    	nMaxNumMaps = Integer.parseInt(maxNumMaps);
	    } catch (NumberFormatException e) {
	    	throw new IOException("mapred.map.max[" + maxNumMaps + "] parse failed! Should be integer.");
	    }    
	    if(nMaxNumMaps != -1 && numMaps > nMaxNumMaps) {
	    	LOG.debug("Maps[" + numMaps + "] more than mapred.map.max[" + nMaxNumMaps + "]!");
	    	throw new IOException("Maps[" + numMaps + "] more than mapred.map.max[" + nMaxNumMaps + "]!");
	    }
	  }

    if(enabledPreHook) {
      try {
        runPreJobHooks(jobId, job, md5s);
      } catch (Exception exp) {
        LOG.warn("Catch an error when running pre hooks.", exp);
      }
    }

    // Write job file to JobTracker's fs        
    out = FileSystem.create(fs, submitJobFile,
        new FsPermission(JOB_FILE_PERMISSION));

    try {
      job.writeXml(out);
    } finally {
      out.close();
    }

    //
    // Now, actually submit the job (using the submit name)
    //
    JobStatus status = jobSubmitClient.submitJob(jobId);
    if (status != null) {
      return new NetworkedJob(status);
    } else {
      throw new IOException("Could not launch job");
    }
  }

  private static void runPreJobHooks(JobID jobId, JobConf job, List<JobFileMD5> md5s)
      throws Exception {
    for (PreJobHook preJob : getPreJobHooks(job)) {
      preJob.run(jobId, job, md5s);
    }
  }

  private static List<PreJobHook> getPreJobHooks(JobConf job)
      throws ClassNotFoundException {
    List<PreJobHook> preJobHooks = new ArrayList<PreJobHook>();
    String[] hookNames = job.getStrings("mapred.client.pre.hooks");
    if (hookNames == null || hookNames.length == 0) {
      return preJobHooks;
    }

    for (String className : hookNames) {
      Class<?> preJobClass = job.getClassByName(className);
      preJobHooks.add((PreJobHook) ReflectionUtils
          .newInstance(preJobClass, job));
    }
    return preJobHooks;
  }

  private static void runPostJobHooks(JobID jobId, JobConf job, RunningJob rj)
      throws Exception {
    for (PostJobHook postJob : getPostJobHooks(job)) {
      postJob.run(jobId, job, rj);
    }
  }

  private static List<PostJobHook> getPostJobHooks(JobConf job)
      throws ClassNotFoundException {
    List<PostJobHook> postJobHooks = new ArrayList<PostJobHook>();
    String[] hookNames = job.getStrings("mapred.client.post.hooks");
    if (hookNames == null || hookNames.length == 0) {
      return postJobHooks;
    }
    
    for (String className : hookNames) {
      Class<?> postJobClass = job.getClassByName(className);
      postJobHooks.add((PostJobHook) ReflectionUtils.newInstance(postJobClass,
          job));
    }
    return postJobHooks;
  }

  /** 
   * Checks if the job directory is clean and has all the required components 
   * for (re) starting the job
   */
  public static boolean isJobDirValid(Path jobDirPath, FileSystem fs) 
  throws IOException {
    FileStatus[] contents = fs.listStatus(jobDirPath);
    int matchCount = 0;
    if (contents != null && contents.length >=3) {
      for (FileStatus status : contents) {
        if ("job.xml".equals(status.getPath().getName())) {
          ++matchCount;
        }
        if ("job.jar".equals(status.getPath().getName())) {
          ++matchCount;
        }
        if ("job.split".equals(status.getPath().getName())) {
          ++matchCount;
        }
      }
      if (matchCount == 3) {
        return true;
      }
    }
    return false;
  }

  static class RawSplit implements Writable {
    private String splitClass;
    private BytesWritable bytes = new BytesWritable();
    private String[] locations;
    long dataLength;

    public void setBytes(byte[] data, int offset, int length) {
      bytes.set(data, offset, length);
    }

    public void setClassName(String className) {
      splitClass = className;
    }
      
    public String getClassName() {
      return splitClass;
    }
      
    public BytesWritable getBytes() {
      return bytes;
    }

    public void clearBytes() {
      bytes = null;
    }
      
    public void setLocations(String[] locations) {
      this.locations = locations;
    }
      
    public String[] getLocations() {
      return locations;
    }
      
    public void readFields(DataInput in) throws IOException {
      splitClass = Text.readString(in);
      dataLength = in.readLong();
      bytes.readFields(in);
      int len = WritableUtils.readVInt(in);
      locations = new String[len];
      for(int i=0; i < len; ++i) {
        locations[i] = Text.readString(in);
      }
    }
      
    public void write(DataOutput out) throws IOException {
      Text.writeString(out, splitClass);
      out.writeLong(dataLength);
      bytes.write(out);
      WritableUtils.writeVInt(out, locations.length);
      for(int i = 0; i < locations.length; i++) {
        Text.writeString(out, locations[i]);
      }        
    }

    public long getDataLength() {
      return dataLength;
    }
    public void setDataLength(long l) {
      dataLength = l;
    }
    
  }
    
  private static final int CURRENT_SPLIT_FILE_VERSION = 0;
  private static final byte[] SPLIT_FILE_HEADER = "SPL".getBytes();
    
  /** Create the list of input splits and write them out in a file for
   *the JobTracker. The format is:
   * <format version>
   * <numSplits>
   * for each split:
   *    <RawSplit>
   * @param splits the input splits to write out
   * @param out the stream to write to
   */
  private void writeSplitsFile(InputSplit[] splits, FSDataOutputStream out) throws IOException {
    out.write(SPLIT_FILE_HEADER);
    WritableUtils.writeVInt(out, CURRENT_SPLIT_FILE_VERSION);
    WritableUtils.writeVInt(out, splits.length);
    DataOutputBuffer buffer = new DataOutputBuffer();
    RawSplit rawSplit = new RawSplit();
    for(InputSplit split: splits) {
      rawSplit.setClassName(split.getClass().getName());
      buffer.reset();
      split.write(buffer);
      rawSplit.setDataLength(split.getLength());
      rawSplit.setBytes(buffer.getData(), 0, buffer.getLength());
      rawSplit.setLocations(split.getLocations());
      rawSplit.write(out);
    }
  }

  /**
   * Read a splits file into a list of raw splits
   * @param in the stream to read from
   * @return the complete list of splits
   * @throws IOException
   */
  static RawSplit[] readSplitFile(DataInput in) throws IOException {
    byte[] header = new byte[SPLIT_FILE_HEADER.length];
    in.readFully(header);
    if (!Arrays.equals(SPLIT_FILE_HEADER, header)) {
      throw new IOException("Invalid header on split file");
    }
    int vers = WritableUtils.readVInt(in);
    if (vers != CURRENT_SPLIT_FILE_VERSION) {
      throw new IOException("Unsupported split version " + vers);
    }
    int len = WritableUtils.readVInt(in);
    RawSplit[] result = new RawSplit[len];
    for(int i=0; i < len; ++i) {
      result[i] = new RawSplit();
      result[i].readFields(in);
    }
    return result;
  }
    
  /**
   * Get an {@link RunningJob} object to track an ongoing job.  Returns
   * null if the id does not correspond to any known job.
   * 
   * @param jobid the jobid of the job.
   * @return the {@link RunningJob} handle to track the job, null if the 
   *         <code>jobid</code> doesn't correspond to any known job.
   * @throws IOException
   */
  public RunningJob getJob(JobID jobid) throws IOException {
    JobStatus status = jobSubmitClient.getJobStatus(jobid);
    if (status != null) {
      return new NetworkedJob(status);
    } else {
      return null;
    }
  }

  /**@deprecated Applications should rather use {@link #getJob(JobID)}. 
   */
  @Deprecated
  public RunningJob getJob(String jobid) throws IOException {
    return getJob(JobID.forName(jobid));
  }
  
  /**
   * Get the information of the current state of the map tasks of a job.
   * 
   * @param jobId the job to query.
   * @return the list of all of the map tips.
   * @throws IOException
   */
  public TaskReport[] getMapTaskReports(JobID jobId) throws IOException {
    return jobSubmitClient.getMapTaskReports(jobId);
  }
  
  /**@deprecated Applications should rather use {@link #getMapTaskReports(JobID)}*/
  @Deprecated
  public TaskReport[] getMapTaskReports(String jobId) throws IOException {
    return getMapTaskReports(JobID.forName(jobId));
  }
  
  /**
   * Get the information of the current state of the reduce tasks of a job.
   * 
   * @param jobId the job to query.
   * @return the list of all of the reduce tips.
   * @throws IOException
   */    
  public TaskReport[] getReduceTaskReports(JobID jobId) throws IOException {
    return jobSubmitClient.getReduceTaskReports(jobId);
  }

  /**
   * Get the information of the current state of the cleanup tasks of a job.
   * 
   * @param jobId the job to query.
   * @return the list of all of the cleanup tips.
   * @throws IOException
   */    
  public TaskReport[] getCleanupTaskReports(JobID jobId) throws IOException {
    return jobSubmitClient.getCleanupTaskReports(jobId);
  }

  /**
   * Get the information of the current state of the setup tasks of a job.
   * 
   * @param jobId the job to query.
   * @return the list of all of the setup tips.
   * @throws IOException
   */    
  public TaskReport[] getSetupTaskReports(JobID jobId) throws IOException {
    return jobSubmitClient.getSetupTaskReports(jobId);
  }

  /**@deprecated Applications should rather use {@link #getReduceTaskReports(JobID)}*/
  @Deprecated
  public TaskReport[] getReduceTaskReports(String jobId) throws IOException {
    return getReduceTaskReports(JobID.forName(jobId));
  }
  
  /**
   * Get status information about the Map-Reduce cluster.
   *  
   * @return the status information about the Map-Reduce cluster as an object
   *         of {@link ClusterStatus}.
   * @throws IOException
   */
  public ClusterStatus getClusterStatus() throws IOException {
    return jobSubmitClient.getClusterStatus();
  }
    

  /** 
   * Get the jobs that are not completed and not failed.
   * 
   * @return array of {@link JobStatus} for the running/to-be-run jobs.
   * @throws IOException
   */
  public JobStatus[] jobsToComplete() throws IOException {
    return jobSubmitClient.jobsToComplete();
  }

  private static void downloadProfile(TaskCompletionEvent e,
                                      String downloadDir) throws IOException  {
    URLConnection connection = 
      new URL(getTaskLogURL(e.getTaskAttemptId(), e.getTaskTrackerHttp()) + 
              "&filter=profile").openConnection();
    InputStream in = connection.getInputStream();
    OutputStream out = new FileOutputStream(downloadDir + "/"
        + e.getTaskAttemptId() + ".profile");
    IOUtils.copyBytes(in, out, 64 * 1024, true);
  }

  /** 
   * Get the jobs that are submitted.
   * 
   * @return array of {@link JobStatus} for the submitted jobs.
   * @throws IOException
   */
  public JobStatus[] getAllJobs() throws IOException {
    return jobSubmitClient.getAllJobs();
  }
  
  /** 
   * Utility that submits a job, then polls for progress until the job is
   * complete.
   * 
   * @param job the job configuration.
   * @throws IOException
   */
  public static RunningJob runJob(JobConf job) throws IOException {
    JobClient jc = new JobClient(job);
    boolean error = true;
    RunningJob running = null;
    JobID jobId = null;
    String lastReport = null;
    final int MAX_RETRIES = 5;
    int retries = MAX_RETRIES;
    TaskStatusFilter filter;
    try {
      filter = getTaskOutputFilter(job);
    } catch(IllegalArgumentException e) {
      LOG.warn("Invalid Output filter : " + e.getMessage() + 
               " Valid values are : NONE, FAILED, SUCCEEDED, ALL");
      throw e;
    }

    try {
      running = jc.submitJob(job);
      jobId = running.getID();
      LOG.info("Running job: " + jobId);
      int eventCounter = 0;
      boolean profiling = job.getProfileEnabled();
      Configuration.IntegerRanges mapRanges = job.getProfileTaskRange(true);
      Configuration.IntegerRanges reduceRanges = job.getProfileTaskRange(false);
      boolean downloadProfiling = job.getBoolean(PROFILING_FILE_DOWNLOAD_ENABLED, true);
      String downloadDir = job.get(PROFILING_FILE_DOWNLOAD_DIR, "/tmp/"
          + System.getProperty("user.name") + "/" + jobId.toString());
      if(profiling) {
        File dir = new File(downloadDir);
        if(!dir.exists())
          dir.mkdirs();
      }

      while (true) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {}
        try {
          if (running.isComplete()) {
            break;
          }
          running = jc.getJob(jobId);
          if (running == null) {
            throw new IOException("Unable to fetch job status from server.");
          }
          String report = 
            (" map " + StringUtils.formatPercent(running.mapProgress(), 0)+
             " reduce " + 
             StringUtils.formatPercent(running.reduceProgress(), 0));
          if (!report.equals(lastReport)) {
            LOG.info(report);
            lastReport = report;
          }
            
          TaskCompletionEvent[] events = 
            running.getTaskCompletionEvents(eventCounter); 
          eventCounter += events.length;
          for(TaskCompletionEvent event : events){
            TaskCompletionEvent.Status status = event.getTaskStatus();
            if (profiling &&
                downloadProfiling &&
                (status == TaskCompletionEvent.Status.SUCCEEDED ||
                 status == TaskCompletionEvent.Status.FAILED) &&
                (event.isMap ? mapRanges : reduceRanges).
                   isIncluded(event.idWithinJob())) {
              downloadProfile(event, downloadDir);
            }
            switch(filter){
            case NONE:
              break;
            case SUCCEEDED:
              if (event.getTaskStatus() == 
                TaskCompletionEvent.Status.SUCCEEDED){
                LOG.info(event.toString());
                displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
              }
              break; 
            case FAILED:
              if (event.getTaskStatus() == 
                TaskCompletionEvent.Status.FAILED){
                LOG.info(event.toString());
                // Displaying the task diagnostic information
                TaskAttemptID taskId = event.getTaskAttemptId();
                String[] taskDiagnostics = 
                  jc.jobSubmitClient.getTaskDiagnostics(taskId); 
                if (taskDiagnostics != null) {
                  for(String diagnostics : taskDiagnostics){
                    System.err.println(diagnostics);
                  }
                }
                // Displaying the task logs
                displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
              }
              break; 
            case KILLED:
              if (event.getTaskStatus() == TaskCompletionEvent.Status.KILLED){
                LOG.info(event.toString());
              }
              break; 
            case ALL:
              LOG.info(event.toString());
              displayTaskLogs(event.getTaskAttemptId(), event.getTaskTrackerHttp());
              break;
            }
          }
          retries = MAX_RETRIES;
        } catch (IOException ie) {
          if (--retries == 0) {
            LOG.warn("Final attempt failed, killing job.");
            throw ie;
          }
          LOG.info("Communication problem with server: " +
                   StringUtils.stringifyException(ie));
        }
      }
      if (!running.isSuccessful()) {
        throw new IOException("Job failed!");
      }
      LOG.info("Job complete: " + jobId);
      running.getCounters().log(LOG);
      error = false;
    } finally {
      if (error && (running != null)) {
        running.killJob();
      }
      jc.close();
    }

    boolean enabledPostHook = job.getBoolean("mapred.client.post.hook.enabled",
        false);
    if(enabledPostHook) {
      // Call the post job hook list
      try {
        if(running != null && jobId != null)
          runPostJobHooks(jobId, job, running);
      } catch (Exception e) {
        LOG.warn("Catch an error when running post hooks", e);
      }
      
    }
    return running;
  }

  static String getTaskLogURL(TaskAttemptID taskId, String baseUrl) {
    return (baseUrl + "/tasklog?plaintext=true&taskid=" + taskId); 
  }
  
  private static void displayTaskLogs(TaskAttemptID taskId, String baseUrl)
    throws IOException {
    // The tasktracker for a 'failed/killed' job might not be around...
    if (baseUrl != null) {
      // Construct the url for the tasklogs
      String taskLogUrl = getTaskLogURL(taskId, baseUrl);
      
      // Copy tasks's stdout of the JobClient
      getTaskLogs(taskId, new URL(taskLogUrl+"&filter=stdout"), System.out);
        
      // Copy task's stderr to stderr of the JobClient 
      getTaskLogs(taskId, new URL(taskLogUrl+"&filter=stderr"), System.err);
    }
  }
    
  private static void getTaskLogs(TaskAttemptID taskId, URL taskLogUrl, 
                                  OutputStream out) {
    try {
      URLConnection connection = taskLogUrl.openConnection();
      connection.setReadTimeout(tasklogtimeout);
      connection.setConnectTimeout(tasklogtimeout);
      BufferedReader input = 
        new BufferedReader(new InputStreamReader(connection.getInputStream()));
      BufferedWriter output = 
        new BufferedWriter(new OutputStreamWriter(out));
      try {
        String logData = null;
        while ((logData = input.readLine()) != null) {
          if (logData.length() > 0) {
            output.write(taskId + ": " + logData + "\n");
            output.flush();
          }
        }
      } finally {
        input.close();
      }
    }catch(IOException ioe){
      LOG.warn("Error reading task output" + ioe.getMessage()); 
    }
  }    

  static Configuration getConfiguration(String jobTrackerSpec)
  {
    Configuration conf = new Configuration();
    if (jobTrackerSpec != null) {        
      if (jobTrackerSpec.indexOf(":") >= 0) {
        conf.set("mapred.job.tracker", jobTrackerSpec);
      } else {
        String classpathFile = "hadoop-" + jobTrackerSpec + ".xml";
        URL validate = conf.getResource(classpathFile);
        if (validate == null) {
          throw new RuntimeException(classpathFile + " not found on CLASSPATH");
        }
        conf.addResource(classpathFile);
      }
    }
    return conf;
  }

  /**
   * Sets the output filter for tasks. only those tasks are printed whose
   * output matches the filter. 
   * @param newValue task filter.
   */
  @Deprecated
  public void setTaskOutputFilter(TaskStatusFilter newValue){
    this.taskOutputFilter = newValue;
  }
    
  /**
   * Get the task output filter out of the JobConf.
   * 
   * @param job the JobConf to examine.
   * @return the filter level.
   */
  public static TaskStatusFilter getTaskOutputFilter(JobConf job) {
    return TaskStatusFilter.valueOf(job.get("jobclient.output.filter", 
                                            "FAILED"));
  }
    
  /**
   * Modify the JobConf to set the task output filter.
   * 
   * @param job the JobConf to modify.
   * @param newValue the value to set.
   */
  public static void setTaskOutputFilter(JobConf job, 
                                         TaskStatusFilter newValue) {
    job.set("jobclient.output.filter", newValue.toString());
  }
    
  /**
   * Returns task output filter.
   * @return task filter. 
   */
  @Deprecated
  public TaskStatusFilter getTaskOutputFilter(){
    return this.taskOutputFilter; 
  }

  private String getJobPriorityNames() {
    StringBuffer sb = new StringBuffer();
    for (JobPriority p : JobPriority.values()) {
      sb.append(p.name()).append(" ");
    }
    return sb.substring(0, sb.length()-1);
  }
  
  /**
   * Display usage of the command-line tool and terminate execution
   */
  private void displayUsage(String cmd) {
    String prefix = "Usage: JobClient ";
    String jobPriorityValues = getJobPriorityNames();
    if("-submit".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-file>]");
    } else if ("-status".equals(cmd) || "-kill".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id>]");
    } else if ("-counter".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id> <group-name> <counter-name>]");
    } else if ("-events".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id> <from-event-#> <#-of-events>]");
    } else if ("-history".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <jobOutputDir>]");
    } else if ("-list".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " [all]]");
    } else if ("-kill-task".equals(cmd) || "-fail-task".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <task-id>]");
    } else if ("-set-priority".equals(cmd)) {
      System.err.println(prefix + "[" + cmd + " <job-id> <priority>]. " +
          "Valid values for priorities are: " 
          + jobPriorityValues); 
    } else {
      System.err.printf(prefix + "<command> <args>\n");
      System.err.printf("\t[-submit <job-file>]\n");
      System.err.printf("\t[-status <job-id>]\n");
      System.err.printf("\t[-counter <job-id> <group-name> <counter-name>]\n");
      System.err.printf("\t[-kill <job-id>]\n");
      System.err.printf("\t[-set-priority <job-id> <priority>]. " +
                                      "Valid values for priorities are: " +
                                      jobPriorityValues + "\n");
      System.err.printf("\t[-events <job-id> <from-event-#> <#-of-events>]\n");
      System.err.printf("\t[-history <jobOutputDir>]\n");
      System.err.printf("\t[-list [all]]\n");
      System.err.printf("\t[-kill-task <task-id>]\n");
      System.err.printf("\t[-fail-task <task-id>]\n\n");
      ToolRunner.printGenericCommandUsage(System.out);
    }
  }
    
  public int run(String[] argv) throws Exception {
    int exitCode = -1;
    if (argv.length < 1) {
      displayUsage("");
      return exitCode;
    }    
    // process arguments
    String cmd = argv[0];
    String submitJobFile = null;
    String jobid = null;
    String taskid = null;
    String outputDir = null;
    String counterGroupName = null;
    String counterName = null;
    String newPriority = null;
    int fromEvent = 0;
    int nEvents = 0;
    boolean getStatus = false;
    boolean getCounter = false;
    boolean killJob = false;
    boolean listEvents = false;
    boolean viewHistory = false;
    boolean viewAllHistory = false;
    boolean listJobs = false;
    boolean listAllJobs = false;
    boolean killTask = false;
    boolean failTask = false;
    boolean setJobPriority = false;

    if ("-submit".equals(cmd)) {
      if (argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      submitJobFile = argv[1];
    } else if ("-status".equals(cmd)) {
      if (argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = argv[1];
      getStatus = true;
    } else if("-counter".equals(cmd)) {
      if (argv.length != 4) {
        displayUsage(cmd);
        return exitCode;
      }
      getCounter = true;
      jobid = argv[1];
      counterGroupName = argv[2];
      counterName = argv[3];
    } else if ("-kill".equals(cmd)) {
      if (argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = argv[1];
      killJob = true;
    } else if ("-set-priority".equals(cmd)) {
      if (argv.length != 3) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = argv[1];
      newPriority = argv[2];
      try {
        JobPriority jp = JobPriority.valueOf(newPriority); 
      } catch (IllegalArgumentException iae) {
        displayUsage(cmd);
        return exitCode;
      }
      setJobPriority = true; 
    } else if ("-events".equals(cmd)) {
      if (argv.length != 4) {
        displayUsage(cmd);
        return exitCode;
      }
      jobid = argv[1];
      fromEvent = Integer.parseInt(argv[2]);
      nEvents = Integer.parseInt(argv[3]);
      listEvents = true;
    } else if ("-history".equals(cmd)) {
      if (argv.length != 2 && !(argv.length == 3 && "all".equals(argv[1]))) {
         displayUsage(cmd);
         return exitCode;
      }
      viewHistory = true;
      if (argv.length == 3 && "all".equals(argv[1])) {
         viewAllHistory = true;
         outputDir = argv[2];
      } else {
         outputDir = argv[1];
      }
    } else if ("-list".equals(cmd)) {
      if (argv.length != 1 && !(argv.length == 2 && "all".equals(argv[1]))) {
        displayUsage(cmd);
        return exitCode;
      }
      if (argv.length == 2 && "all".equals(argv[1])) {
        listAllJobs = true;
      } else {
        listJobs = true;
      }
    } else if("-kill-task".equals(cmd)) {
      if(argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      killTask = true;
      taskid = argv[1];
    } else if("-fail-task".equals(cmd)) {
      if(argv.length != 2) {
        displayUsage(cmd);
        return exitCode;
      }
      failTask = true;
      taskid = argv[1];
    } else {
      displayUsage(cmd);
      return exitCode;
    }

    // initialize JobClient
    JobConf conf = null;
    if (submitJobFile != null) {
      conf = new JobConf(submitJobFile);
    } else {
      conf = new JobConf(getConf());
    }
    init(conf);
        
    // Submit the request
    try {
      if (submitJobFile != null) {
        RunningJob job = submitJob(conf);
        System.out.println("Created job " + job.getID());
        exitCode = 0;
      } else if (getStatus) {
        RunningJob job = getJob(JobID.forName(jobid));
        if (job == null) {
          System.out.println("Could not find job " + jobid);
        } else {
          System.out.println();
          System.out.println(job);
          System.out.println(job.getCounters());
          exitCode = 0;
        }
      } else if (getCounter) {
        RunningJob job = getJob(JobID.forName(jobid));
        if (job == null) {
          System.out.println("Could not find job " + jobid);
        } else {
          Counters counters = job.getCounters();
          Group group = counters.getGroup(counterGroupName);
          Counter counter = group.getCounterForName(counterName);
          System.out.println(counter.getCounter());
          exitCode = 0;
        }
      } else if (killJob) {
        RunningJob job = getJob(JobID.forName(jobid));
        if (job == null) {
          System.out.println("Could not find job " + jobid);
        } else {
          job.killJob();
          System.out.println("Killed job " + jobid);
          exitCode = 0;
        }
      } else if (setJobPriority) {
        RunningJob job = getJob(JobID.forName(jobid));
        if (job == null) {
          System.out.println("Could not find job " + jobid);
        } else {
          job.setJobPriority(newPriority);
          System.out.println("Changed job priority.");
          exitCode = 0;
        } 
      } else if (viewHistory) {
        viewHistory(outputDir, viewAllHistory);
        exitCode = 0;
      } else if (listEvents) {
        listEvents(JobID.forName(jobid), fromEvent, nEvents);
        exitCode = 0;
      } else if (listJobs) {
        listJobs();
        exitCode = 0;
      } else if (listAllJobs) {
          listAllJobs();
          exitCode = 0;
      } else if(killTask) {
        if(jobSubmitClient.killTask(TaskAttemptID.forName(taskid), false)) {
          System.out.println("Killed task " + taskid);
          exitCode = 0;
        } else {
          System.out.println("Could not kill task " + taskid);
          exitCode = -1;
        }
      } else if(failTask) {
        if(jobSubmitClient.killTask(TaskAttemptID.forName(taskid), true)) {
          System.out.println("Killed task " + taskid + " by failing it");
          exitCode = 0;
        } else {
          System.out.println("Could not fail task " + taskid);
          exitCode = -1;
        }
      }
    } finally {
      close();
    }
    return exitCode;
  }

  private void viewHistory(String outputDir, boolean all) 
    throws IOException {
    HistoryViewer historyViewer = new HistoryViewer(outputDir,
                                        getConf(), all);
    historyViewer.print();
  }
  
  /**
   * List the events for the given job
   * @param jobId the job id for the job's events to list
   * @throws IOException
   */
  private void listEvents(JobID jobId, int fromEventId, int numEvents)
    throws IOException {
    TaskCompletionEvent[] events = 
      jobSubmitClient.getTaskCompletionEvents(jobId, fromEventId, numEvents);
    System.out.println("Task completion events for " + jobId);
    System.out.println("Number of events (from " + fromEventId + 
                       ") are: " + events.length);
    for(TaskCompletionEvent event: events) {
      System.out.println(event.getTaskStatus() + " " + event.getTaskAttemptId() + " " + 
                         getTaskLogURL(event.getTaskAttemptId(), 
                                       event.getTaskTrackerHttp()));
    }
  }

  /**
   * Dump a list of currently running jobs
   * @throws IOException
   */
  private void listJobs() throws IOException {
    JobStatus[] jobs = jobsToComplete();
    if (jobs == null)
      jobs = new JobStatus[0];

    System.out.printf("%d jobs currently running\n", jobs.length);
    displayJobList(jobs);
  }
    
  /**
   * Dump a list of all jobs submitted.
   * @throws IOException
   */
  private void listAllJobs() throws IOException {
    JobStatus[] jobs = getAllJobs();
    if (jobs == null)
      jobs = new JobStatus[0];
    System.out.printf("%d jobs submitted\n", jobs.length);
    System.out.printf("States are:\n\tRunning : 1\tSucceded : 2" +
    "\tFailed : 3\tPrep : 4\n");
    displayJobList(jobs);
  }

  void displayJobList(JobStatus[] jobs) {
    System.out.printf("JobId\tState\tStartTime\tUserName\tPriority\tSchedulingInfo\n");
    for (JobStatus job : jobs) {
      System.out.printf("%s\t%d\t%d\t%s\t%s\t%s\n", job.getJobID(), job.getRunState(),
          job.getStartTime(), job.getUsername(), 
          job.getJobPriority().name(), job.getSchedulingInfo());
    }
  }

  /**
   * Get status information about the max available Maps in the cluster.
   *  
   * @return the max available Maps in the cluster
   * @throws IOException
   */
  public int getDefaultMaps() throws IOException {
    return getClusterStatus().getMaxMapTasks();
  }

  /**
   * Get status information about the max available Reduces in the cluster.
   *  
   * @return the max available Reduces in the cluster
   * @throws IOException
   */
  public int getDefaultReduces() throws IOException {
    return getClusterStatus().getMaxReduceTasks();
  }

  /**
   * Grab the jobtracker system directory path where job-specific files are to be placed.
   * 
   * @return the system directory where job-specific files are to be placed.
   */
  public Path getSystemDir() {
    if (sysDir == null) {
      sysDir = new Path(jobSubmitClient.getSystemDir());
    }
    return sysDir;
  }
  
  
  /**
   * Return an array of queue information objects about all the Job Queues
   * configured.
   * 
   * @return Array of JobQueueInfo objects
   * @throws IOException
   */
  public JobQueueInfo[] getQueues() throws IOException {
    return jobSubmitClient.getQueues();
  }
  
  /**
   * Gets all the jobs which were added to particular Job Queue
   * 
   * @param queueName name of the Job Queue
   * @return Array of jobs present in the job queue
   * @throws IOException
   */
  
  public JobStatus[] getJobsFromQueue(String queueName) throws IOException {
    return jobSubmitClient.getJobsFromQueue(queueName);
  }
  
  /**
   * Gets the queue information associated to a particular Job Queue
   * 
   * @param queueName name of the job queue.
   * @return Queue information associated to particular queue.
   * @throws IOException
   */
  public JobQueueInfo getQueueInfo(String queueName) throws IOException {
    return jobSubmitClient.getQueueInfo(queueName);
  }
  

  /**
   */
  public static void main(String argv[]) throws Exception {
    int res = ToolRunner.run(new JobClient(), argv);
    System.exit(res);
  }
}

