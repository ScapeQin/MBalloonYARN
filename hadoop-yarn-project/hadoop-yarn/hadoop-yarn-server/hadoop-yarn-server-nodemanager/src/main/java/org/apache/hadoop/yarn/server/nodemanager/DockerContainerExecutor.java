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

package org.apache.hadoop.yarn.server.nodemanager;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

import org.apache.commons.lang.math.RandomUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.util.Shell.ShellCommandExecutor;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerDiagnosticsUpdateEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.launcher.ContainerLaunch;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.localizer.ContainerLocalizer;
import org.apache.hadoop.yarn.util.ConverterUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.net.InetSocketAddress;
import static org.apache.hadoop.fs.CreateFlag.CREATE;
import static org.apache.hadoop.fs.CreateFlag.OVERWRITE;

/**
 * This executor will launch a docker container and run the task inside the container.
 */
public class DockerContainerExecutor extends ContainerExecutor {

  private static final Log LOG = LogFactory
      .getLog(DockerContainerExecutor.class);
  public static final String DOCKER_CONTAINER_EXECUTOR_SCRIPT = "docker_container_executor";
  public static final String DOCKER_CONTAINER_EXECUTOR_SESSION_SCRIPT = "docker_container_executor_session";

  // This validates that the image is a proper docker image and would not crash docker.
  public static final String DOCKER_IMAGE_PATTERN = "^(([\\w\\.-]+)(:\\d+)*\\/)?[\\w\\.:-]+$";


  private final FileContext lfs;
  private final Pattern dockerImagePattern;
  

  public DockerContainerExecutor() {
    try {
      this.lfs = FileContext.getLocalFSFileContext();
      this.dockerImagePattern = Pattern.compile(DOCKER_IMAGE_PATTERN);
    } catch (UnsupportedFileSystemException e) {
      throw new RuntimeException(e);
    }
  }

  protected void copyFile(Path src, Path dst, String owner) throws IOException {
    lfs.util().copy(src, dst);
  }

  @Override
  public void init() throws IOException {
    String auth = getConf().get(CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION);
    if (auth != null && !auth.equals("simple")) {
      throw new IllegalStateException("DockerContainerExecutor only works with simple authentication mode");
    }
    String dockerExecutor = getConf().get(YarnConfiguration.NM_DOCKER_CONTAINER_EXECUTOR_EXEC_NAME,
      YarnConfiguration.NM_DEFAULT_DOCKER_CONTAINER_EXECUTOR_EXEC_NAME);
    if (!new File(dockerExecutor).exists()) {
      throw new IllegalStateException("Invalid docker exec path: " + dockerExecutor);
    }
  }

  @Override
  public synchronized void startLocalizer(Path nmPrivateContainerTokensPath,
                                          InetSocketAddress nmAddr, String user, String appId, String locId,
                                          LocalDirsHandlerService dirsHandler)
    throws IOException, InterruptedException {

    List<String> localDirs = dirsHandler.getLocalDirs();
    List<String> logDirs = dirsHandler.getLogDirs();

    ContainerLocalizer localizer =
      new ContainerLocalizer(lfs, user, appId, locId, getPaths(localDirs),
        RecordFactoryProvider.getRecordFactory(getConf()));

    createUserLocalDirs(localDirs, user);
    createUserCacheDirs(localDirs, user);
    createAppDirs(localDirs, user, appId);
    createAppLogDirs(appId, logDirs, user);

    // randomly choose the local directory
    Path appStorageDir = getWorkingDir(localDirs, user, appId);

    String tokenFn = String.format(ContainerLocalizer.TOKEN_FILE_NAME_FMT, locId);
    Path tokenDst = new Path(appStorageDir, tokenFn);
    copyFile(nmPrivateContainerTokensPath, tokenDst, user);
    //LOG.info("Copying from " + nmPrivateContainerTokensPath + " to " + tokenDst);
    lfs.setWorkingDirectory(appStorageDir);
    //LOG.info("CWD set to " + appStorageDir + " = " + lfs.getWorkingDirectory());
    // TODO: DO it over RPC for maintaining similarity?
    localizer.runLocalization(nmAddr);
  }


  @Override
  public int launchContainer(Container container,
                             Path nmPrivateContainerScriptPath, Path nmPrivateTokensPath,
                             String userName, String appId, Path containerWorkDir,
                             List<String> localDirs, List<String> logDirs) throws IOException {
    
	//LOG.info("start launching");
	String containerImageName = getConf().get(YarnConfiguration.NM_DOCKER_CONTAINER_EXECUTOR_IMAGE_NAME,
    		           YarnConfiguration.NM_DEFAULT_DOCKER_CONTAINER_EXECUTOR_IMAGE_NAME);
   
    //LOG.info("containerImageName from launchContext: " + containerImageName);
    
    Preconditions.checkArgument(!Strings.isNullOrEmpty(containerImageName), "Container image must not be null");
    containerImageName = containerImageName.replaceAll("['\"]", "");

    Preconditions.checkArgument(saneDockerImage(containerImageName), "Image: " + containerImageName + " is not a proper docker image");
    String dockerExecutor = getConf().get(YarnConfiguration.NM_DOCKER_CONTAINER_EXECUTOR_EXEC_NAME,
        YarnConfiguration.NM_DEFAULT_DOCKER_CONTAINER_EXECUTOR_EXEC_NAME);

    FsPermission dirPerm = new FsPermission(APPDIR_PERM);
    ContainerId containerId = container.getContainerId();

    // create container dirs on all disks
    String containerIdStr = ConverterUtils.toString(containerId);
    String appIdStr =
        ConverterUtils.toString(
            containerId.getApplicationAttemptId().
                getApplicationId());
    for (String sLocalDir : localDirs) {
      Path usersdir = new Path(sLocalDir, ContainerLocalizer.USERCACHE);
      Path userdir = new Path(usersdir, userName);
      Path appCacheDir = new Path(userdir, ContainerLocalizer.APPCACHE);
      Path appDir = new Path(appCacheDir, appIdStr);
      Path containerDir = new Path(appDir, containerIdStr);
      createDir(containerDir, dirPerm, true, userName);
    }

    // Create the container log-dirs on all disks
    createContainerLogDirs(appIdStr, containerIdStr, logDirs, userName);

    Path tmpDir = new Path(containerWorkDir,
        YarnConfiguration.DEFAULT_CONTAINER_TEMP_DIR);
    createDir(tmpDir, dirPerm, false, userName);

    // copy launch script to work dir
    Path launchDst =
        new Path(containerWorkDir, ContainerLaunch.CONTAINER_SCRIPT);
    lfs.util().copy(nmPrivateContainerScriptPath, launchDst);

    // copy container tokens to work dir
    Path tokenDst =
        new Path(containerWorkDir, ContainerLaunch.FINAL_CONTAINER_TOKENS_FILE);
    lfs.util().copy(nmPrivateTokensPath, tokenDst);
    
    //configure the memory
    String memory;
    int staticMemory = getConf().getInt(YarnConfiguration.STATIC_CONTAINER_MEM_SIZE, 
    		                            YarnConfiguration.DEFAULT_STATIC_CONTAINER_MEM_SIZE);

    //for static container test
    memory =staticMemory > 0 ? Integer.toString(staticMemory):
    			                    Integer.toString(container.getResource().getMemory());
    


    String localDirMount = toMount(localDirs);
    String logDirMount = toMount(logDirs);
    String containerWorkDirMount = toMount(Collections.singletonList(containerWorkDir.toUri().getPath()));
    StringBuilder commands = new StringBuilder();
    String commandStr = commands.append(dockerExecutor)
        .append(" ")
        .append("run")
        .append(" ")
        .append("--memory="+memory+"m")
        .append(" ")
        .append("--memory-swap -1")
        .append(" ")
        .append("--oom-kill-disable")
        .append(" ")
        .append("--rm --net=host")
        .append(" ")
        .append(" --name " + containerIdStr)
        .append(localDirMount)
        .append(logDirMount)
        .append(containerWorkDirMount)
        .append(" ")
        .append(containerImageName)
        .toString();
    //LOG.info("command strings "+commandStr);
    String dockerPidScript = "`" + dockerExecutor + " inspect --format {{.State.Pid}} " + containerIdStr + "`";
    // Create new local launch wrapper script
    LocalWrapperScriptBuilder sb =
      new UnixLocalWrapperScriptBuilder(containerWorkDir, commandStr, dockerPidScript);
    Path pidFile = getPidFilePath(containerId);
    if (pidFile != null) {
      sb.writeLocalWrapperScript(launchDst, pidFile);
    } else {
      LOG.info("Container " + containerIdStr
          + " was marked as inactive. Returning terminated error");
      return ExitCode.TERMINATED.getExitCode();
    }
    
    ShellCommandExecutor shExec = null;
    try {
      lfs.setPermission(launchDst,
          ContainerExecutor.TASK_LAUNCH_SCRIPT_PERMISSION);
      lfs.setPermission(sb.getWrapperScriptPath(),
          ContainerExecutor.TASK_LAUNCH_SCRIPT_PERMISSION);

      // Setup command to run
      String[] command = getRunCommand(sb.getWrapperScriptPath().toString(),
        containerIdStr, userName, pidFile, this.getConf());
      if (LOG.isDebugEnabled()) {
        LOG.debug("launchContainer: " + commandStr + " " + Joiner.on(" ").join(command));
      }
      
      //Thread.sleep(1000000);
      
      shExec = new ShellCommandExecutor(
        command,
        new File(containerWorkDir.toUri().getPath()),
        container.getLaunchContext().getEnvironment(),      // sanitized env
        0L,
        false);
      if (isContainerActive(containerId)) {
        shExec.execute();
      } else {
        LOG.info("Container " + containerIdStr +
            " was marked as inactive. Returning terminated error");
        return ExitCode.TERMINATED.getExitCode();
      }
    } catch (IOException e) {
      if (null == shExec) {
        return -1;
      }
      int exitCode = shExec.getExitCode();
      LOG.warn("Exit code from container " + containerId + " is : " + exitCode);
      // 143 (SIGTERM) and 137 (SIGKILL) exit codes means the container was
      // terminated/killed forcefully. In all other cases, log the
      // container-executor's output
      if (exitCode != ExitCode.FORCE_KILLED.getExitCode()
          && exitCode != ExitCode.TERMINATED.getExitCode()) {
        LOG.warn("Exception from container-launch with container ID: "
            + containerId + " and exit code: " + exitCode, e);
        logOutput(shExec.getOutput());
        String diagnostics = "Exception from container-launch: \n"
            + StringUtils.stringifyException(e) + "\n" + shExec.getOutput();
        container.handle(new ContainerDiagnosticsUpdateEvent(containerId,
            diagnostics));
      } else {
        container.handle(new ContainerDiagnosticsUpdateEvent(containerId,
            "Container killed on request. Exit code is " + exitCode));
      }
      return exitCode;
    } finally {
      if (shExec != null) {
        shExec.close();
      }
    }
    return 0;
  }
  
  

  @Override
  public void writeLaunchEnv(Container container, OutputStream out, Map<String, String> environment, Map<Path, List<String>> resources, List<String> command) throws IOException {
    ContainerLaunch.ShellScriptBuilder sb = ContainerLaunch.ShellScriptBuilder.create();

    LOG.info("starting write our launch env"); 
    Set<String> exclusionSet = new HashSet<String>();
    exclusionSet.add(YarnConfiguration.NM_DOCKER_CONTAINER_EXECUTOR_IMAGE_NAME);
    exclusionSet.add(ApplicationConstants.Environment.HADOOP_YARN_HOME.name());
    exclusionSet.add(ApplicationConstants.Environment.HADOOP_COMMON_HOME.name());
    exclusionSet.add(ApplicationConstants.Environment.HADOOP_HDFS_HOME.name());
    exclusionSet.add(ApplicationConstants.Environment.HADOOP_CONF_DIR.name());
    exclusionSet.add(ApplicationConstants.Environment.JAVA_HOME.name());

    if (environment != null) {
      String pwdKey="PWD";
     
      //we put pwd first
      if(environment.get(pwdKey)!=null){
    	  //LOG.info("find PWD");
    	  sb.env(pwdKey.toString(), environment.get(pwdKey).toString());
    	  environment.remove(pwdKey);
      }
     
      //we put the reset keys
      for (Map.Entry<String,String> env : environment.entrySet()) {
        if (!exclusionSet.contains(env.getKey())) {
          sb.env(env.getKey().toString(), env.getValue().toString());
        }
      }
    }
    
    //cd to $PWD
    sb.cd("$PWD");
    //LOG.info("try to cd to $PWD");
    //
    if (resources != null) {
      for (Map.Entry<Path,List<String>> entry : resources.entrySet()) {
        for (String linkName : entry.getValue()) {
          sb.symlink(entry.getKey(), new Path(linkName));
        }
      }
    }

    //we hacked flexible container's 
    //jvm heap here
    if(container.isFlexble()){
    	//LOG.info("update jvm heap");
        int jvmHeap = getConf().getInt(
        		                       YarnConfiguration.FELXI_CONTAINER_HEAP_SIZE, 
        		                       YarnConfiguration.DEFAULT_FELXI_CONTAINER_HEAP_SIZE
        		                      );
        for(int i=0;i<command.size();i++)
        {
        	if(command.get(i).contains("-Xmx")){
        		//LOG.info("find jvm str"+command.get(i));
        		command.set(i,"-Xmx"+Integer.toString(jvmHeap)+"m");
        	}
        }
    	
    }
   
    sb.command(command);

    PrintStream pout = null;
    PrintStream ps = null;
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      pout = new PrintStream(out, false, "UTF-8");
      if (LOG.isDebugEnabled()) {
        ps = new PrintStream(baos, false, "UTF-8");
        sb.write(ps);
      }
      sb.write(pout);

    } finally {
      if (out != null) {
        out.close();
      }
      if (ps != null) {
        ps.close();
      }
    }
    {
      //LOG.info("Script: " + baos.toString("UTF-8"));
    }
  }

  private boolean saneDockerImage(String containerImageName) {
    return dockerImagePattern.matcher(containerImageName).matches();
  }

  @Override
  public boolean signalContainer(String user, String pid, Signal signal)
    throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Sending signal " + signal.getValue() + " to pid " + pid
        + " as user " + user);
    }
    if (!containerIsAlive(pid)) {
      return false;
    }
    try {
      killContainer(pid, signal);
    } catch (IOException e) {
      if (!containerIsAlive(pid)) {
        return false;
      }
      throw e;
    }
    return true;
  }

  @Override
  public boolean isContainerProcessAlive(String user, String pid)
    throws IOException {
    return containerIsAlive(pid);
  }

  /**
   * Returns true if the process with the specified pid is alive.
   *
   * @param pid String pid
   * @return boolean true if the process is alive
   */
  @VisibleForTesting
  public static boolean containerIsAlive(String pid) throws IOException {
    try {
      new ShellCommandExecutor(Shell.getCheckProcessIsAliveCommand(pid))
        .execute();
      // successful execution means process is alive
      return true;
    }
    catch (Shell.ExitCodeException e) {
      // failure (non-zero exit code) means process is not alive
      return false;
    }
  }

  /**
   * Send a specified signal to the specified pid
   *
   * @param pid the pid of the process [group] to signal.
   * @param signal signal to send
   * (for logging).
   */
  protected void killContainer(String pid, Signal signal) throws IOException {
    new ShellCommandExecutor(Shell.getSignalKillCommand(signal.getValue(), pid))
      .execute();
  }

  @Override
  public void deleteAsUser(String user, Path subDir, Path... baseDirs)
    throws IOException, InterruptedException {
    if (baseDirs == null || baseDirs.length == 0) {
      LOG.info("Deleting absolute path : " + subDir);
      if (!lfs.delete(subDir, true)) {
        //Maybe retry
        LOG.warn("delete returned false for path: [" + subDir + "]");
      }
      return;
    }
    for (Path baseDir : baseDirs) {
      Path del = subDir == null ? baseDir : new Path(baseDir, subDir);
      LOG.info("Deleting path : " + del);
      try {
        if (!lfs.delete(del, true)) {
          LOG.warn("delete returned false for path: [" + del + "]");
        }
      } catch (FileNotFoundException e) {
        continue;
      }
    }
  }

  /**
   * Converts a directory list to a docker mount string
   * @param dirs
   * @return a string of mounts for docker
   */
  private String toMount(List<String> dirs) {
    StringBuilder builder = new StringBuilder();
    for (String dir : dirs) {
      builder.append(" -v " + dir + ":" + dir);
    }
    return builder.toString();
  }

  private abstract class LocalWrapperScriptBuilder {

    private final Path wrapperScriptPath;

    public Path getWrapperScriptPath() {
      return wrapperScriptPath;
    }

    public void writeLocalWrapperScript(Path launchDst, Path pidFile) throws IOException {
      DataOutputStream out = null;
      PrintStream pout = null;

      try {
        out = lfs.create(wrapperScriptPath, EnumSet.of(CREATE, OVERWRITE));
        pout = new PrintStream(out, false, "UTF-8");
        writeLocalWrapperScript(launchDst, pidFile, pout);
      } finally {
        IOUtils.cleanup(LOG, pout, out);
      }
    }

    protected abstract void writeLocalWrapperScript(Path launchDst, Path pidFile,
                                                    PrintStream pout);

    protected LocalWrapperScriptBuilder(Path containerWorkDir) {
      this.wrapperScriptPath = new Path(containerWorkDir,
          Shell.appendScriptExtension(DOCKER_CONTAINER_EXECUTOR_SCRIPT));
    }
  }

  private final class UnixLocalWrapperScriptBuilder
      extends LocalWrapperScriptBuilder {
    private final Path sessionScriptPath;
    private final String dockerCommand;
    private final String dockerPidScript;

    public UnixLocalWrapperScriptBuilder(Path containerWorkDir, String dockerCommand, String dockerPidScript) {
      super(containerWorkDir);
      this.dockerCommand = dockerCommand;
      this.dockerPidScript = dockerPidScript;
      this.sessionScriptPath = new Path(containerWorkDir,
        Shell.appendScriptExtension(DOCKER_CONTAINER_EXECUTOR_SESSION_SCRIPT));
    }

    @Override
    public void writeLocalWrapperScript(Path launchDst, Path pidFile)
      throws IOException {
      writeSessionScript(launchDst, pidFile);
      super.writeLocalWrapperScript(launchDst, pidFile);
    }

    @Override
    public void writeLocalWrapperScript(Path launchDst, Path pidFile,
                                        PrintStream pout) {

      String exitCodeFile = ContainerLaunch.getExitCodeFile(
        pidFile.toString());
      String tmpFile = exitCodeFile + ".tmp";
      pout.println("#!/usr/bin/env bash");
      pout.println("bash \"" + sessionScriptPath.toString() + "\"");
      pout.println("rc=$?");
      pout.println("echo $rc > \"" + tmpFile + "\"");
      pout.println("mv -f \"" + tmpFile + "\" \"" + exitCodeFile + "\"");
      pout.println("exit $rc");
    }

    private void writeSessionScript(Path launchDst, Path pidFile)
      throws IOException {
      DataOutputStream out = null;
      PrintStream pout = null;
      try {
        out = lfs.create(sessionScriptPath, EnumSet.of(CREATE, OVERWRITE));
        pout = new PrintStream(out, false, "UTF-8");
        // We need to do a move as writing to a file is not atomic
        // Process reading a file being written to may get garbled data
        // hence write pid to tmp file first followed by a mv
        pout.println("#!/usr/bin/env bash");
        pout.println();
        pout.println("echo "+ dockerPidScript +" > " + pidFile.toString() + ".tmp");
        pout.println("/bin/mv -f " + pidFile.toString() + ".tmp " + pidFile);
        pout.println(dockerCommand + " bash \"" +
          launchDst.toUri().getPath().toString() + "\"");
      } finally {
        IOUtils.cleanup(LOG, pout, out);
      }
      lfs.setPermission(sessionScriptPath,
        ContainerExecutor.TASK_LAUNCH_SCRIPT_PERMISSION);
    }
  }

  protected void createDir(Path dirPath, FsPermission perms,
                           boolean createParent, String user) throws IOException {
    lfs.mkdir(dirPath, perms, createParent);
    if (!perms.equals(perms.applyUMask(lfs.getUMask()))) {
      lfs.setPermission(dirPath, perms);
    }
  }

  /**
   * Initialize the local directories for a particular user.
   * <ul>.mkdir
   * <li>$local.dir/usercache/$user</li>
   * </ul>
   */
  void createUserLocalDirs(List<String> localDirs, String user)
      throws IOException {
    boolean userDirStatus = false;
    FsPermission userperms = new FsPermission(USER_PERM);
    for (String localDir : localDirs) {
      // create $local.dir/usercache/$user and its immediate parent
      try {
        createDir(getUserCacheDir(new Path(localDir), user), userperms, true, user);
      } catch (IOException e) {
        LOG.warn("Unable to create the user directory : " + localDir, e);
        continue;
      }
      userDirStatus = true;
    }
    if (!userDirStatus) {
      throw new IOException("Not able to initialize user directories "
          + "in any of the configured local directories for user " + user);
    }
  }


  /**
   * Initialize the local cache directories for a particular user.
   * <ul>
   * <li>$local.dir/usercache/$user</li>
   * <li>$local.dir/usercache/$user/appcache</li>
   * <li>$local.dir/usercache/$user/filecache</li>
   * </ul>
   */
  void createUserCacheDirs(List<String> localDirs, String user)
    throws IOException {
    LOG.info("Initializing user " + user);

    boolean appcacheDirStatus = false;
    boolean distributedCacheDirStatus = false;
    FsPermission appCachePerms = new FsPermission(APPCACHE_PERM);
    FsPermission fileperms = new FsPermission(FILECACHE_PERM);

    for (String localDir : localDirs) {
      // create $local.dir/usercache/$user/appcache
      Path localDirPath = new Path(localDir);
      final Path appDir = getAppcacheDir(localDirPath, user);
      try {
        createDir(appDir, appCachePerms, true, user);
        appcacheDirStatus = true;
      } catch (IOException e) {
        LOG.warn("Unable to create app cache directory : " + appDir, e);
      }
      // create $local.dir/usercache/$user/filecache
      final Path distDir = getFileCacheDir(localDirPath, user);
      try {
        createDir(distDir, fileperms, true, user);
        distributedCacheDirStatus = true;
      } catch (IOException e) {
        LOG.warn("Unable to create file cache directory : " + distDir, e);
      }
    }
    if (!appcacheDirStatus) {
      throw new IOException("Not able to initialize app-cache directories "
        + "in any of the configured local directories for user " + user);
    }
    if (!distributedCacheDirStatus) {
      throw new IOException(
        "Not able to initialize distributed-cache directories "
          + "in any of the configured local directories for user "
          + user);
    }
  }

  /**
   * Initialize the local directories for a particular user.
   * <ul>
   * <li>$local.dir/usercache/$user/appcache/$appid</li>
   * </ul>
   * @param localDirs
   */
  void createAppDirs(List<String> localDirs, String user, String appId)
    throws IOException {
    boolean initAppDirStatus = false;
    FsPermission appperms = new FsPermission(APPDIR_PERM);
    for (String localDir : localDirs) {
      Path fullAppDir = getApplicationDir(new Path(localDir), user, appId);
      // create $local.dir/usercache/$user/appcache/$appId
      try {
        createDir(fullAppDir, appperms, true, user);
        initAppDirStatus = true;
      } catch (IOException e) {
        LOG.warn("Unable to create app directory " + fullAppDir.toString(), e);
      }
    }
    if (!initAppDirStatus) {
      throw new IOException("Not able to initialize app directories "
        + "in any of the configured local directories for app "
        + appId.toString());
    }
  }


  /**
   * Create application log directories on all disks.
   */
  void createContainerLogDirs(String appId, String containerId,
                              List<String> logDirs, String user) throws IOException {

    boolean containerLogDirStatus = false;
    FsPermission containerLogDirPerms = new FsPermission(LOGDIR_PERM);
    for (String rootLogDir : logDirs) {
      // create $log.dir/$appid/$containerid
      Path appLogDir = new Path(rootLogDir, appId);
      Path containerLogDir = new Path(appLogDir, containerId);
      try {
        createDir(containerLogDir, containerLogDirPerms, true, user);
      } catch (IOException e) {
        LOG.warn("Unable to create the container-log directory : "
          + appLogDir, e);
        continue;
      }
      containerLogDirStatus = true;
    }
    if (!containerLogDirStatus) {
      throw new IOException(
        "Not able to initialize container-log directories "
          + "in any of the configured local directories for container "
          + containerId);
    }
  }

  /**
   * Permissions for user dir.
   * $local.dir/usercache/$user
   */
  static final short USER_PERM = (short) 0750;
  /**
   * Permissions for user appcache dir.
   * $local.dir/usercache/$user/appcache
   */
  static final short APPCACHE_PERM = (short) 0710;
  /**
   * Permissions for user filecache dir.
   * $local.dir/usercache/$user/filecache
   */
  static final short FILECACHE_PERM = (short) 0710;
  /**
   * Permissions for user app dir.
   * $local.dir/usercache/$user/appcache/$appId
   */
  static final short APPDIR_PERM = (short) 0710;
  /**
   * Permissions for user log dir.
   * $logdir/$user/$appId
   */
  static final short LOGDIR_PERM = (short) 0710;

  private long getDiskFreeSpace(Path base) throws IOException {
    return lfs.getFsStatus(base).getRemaining();
  }

  private Path getApplicationDir(Path base, String user, String appId) {
    return new Path(getAppcacheDir(base, user), appId);
  }

  private Path getUserCacheDir(Path base, String user) {
    return new Path(new Path(base, ContainerLocalizer.USERCACHE), user);
  }

  private Path getAppcacheDir(Path base, String user) {
    return new Path(getUserCacheDir(base, user),
        ContainerLocalizer.APPCACHE);
  }

  private Path getFileCacheDir(Path base, String user) {
    return new Path(getUserCacheDir(base, user),
        ContainerLocalizer.FILECACHE);
  }

  protected Path getWorkingDir(List<String> localDirs, String user,
                               String appId) throws IOException {
    Path appStorageDir = null;
    long totalAvailable = 0L;
    long[] availableOnDisk = new long[localDirs.size()];
    int i = 0;
    // randomly choose the app directory
    // the chance of picking a directory is proportional to
    // the available space on the directory.
    // firstly calculate the sum of all available space on these directories
    for (String localDir : localDirs) {
      Path curBase = getApplicationDir(new Path(localDir),
        user, appId);
      long space = 0L;
      try {
        space = getDiskFreeSpace(curBase);
      } catch (IOException e) {
        LOG.warn("Unable to get Free Space for " + curBase.toString(), e);
      }
      availableOnDisk[i++] = space;
      totalAvailable += space;
    }

    // throw an IOException if totalAvailable is 0.
    if (totalAvailable <= 0L) {
      throw new IOException("Not able to find a working directory for "
        + user);
    }

    // make probability to pick a directory proportional to
    // the available space on the directory.
    long randomPosition = RandomUtils.nextLong() % totalAvailable;
    int dir = 0;
    // skip zero available space directory,
    // because totalAvailable is greater than 0 and randomPosition
    // is less than totalAvailable, we can find a valid directory
    // with nonzero available space.
    while (availableOnDisk[dir] == 0L) {
      dir++;
    }
    while (randomPosition > availableOnDisk[dir]) {
      randomPosition -= availableOnDisk[dir++];
    }
    appStorageDir = getApplicationDir(new Path(localDirs.get(dir)),
      user, appId);

    return appStorageDir;
  }

  /**
   * Create application log directories on all disks.
   */
  void createAppLogDirs(String appId, List<String> logDirs, String user)
    throws IOException {

    boolean appLogDirStatus = false;
    FsPermission appLogDirPerms = new FsPermission(LOGDIR_PERM);
    for (String rootLogDir : logDirs) {
      // create $log.dir/$appid
      Path appLogDir = new Path(rootLogDir, appId);
      try {
        createDir(appLogDir, appLogDirPerms, true, user);
      } catch (IOException e) {
        LOG.warn("Unable to create the app-log directory : " + appLogDir, e);
        continue;
      }
      appLogDirStatus = true;
    }
    if (!appLogDirStatus) {
      throw new IOException("Not able to initialize app-log directories "
        + "in any of the configured local directories for app " + appId);
    }
  }

  /**
   * @return the list of paths of given local directories
   */
  private static List<Path> getPaths(List<String> dirs) {
    List<Path> paths = new ArrayList<Path>(dirs.size());
    for (int i = 0; i < dirs.size(); i++) {
      paths.add(new Path(dirs.get(i)));
    }
    return paths;
  }

}