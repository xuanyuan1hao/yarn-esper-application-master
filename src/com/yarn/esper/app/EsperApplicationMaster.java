package com.yarn.esper.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.ExitUtil;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.apache.hadoop.yarn.api.ApplicationConstants.Environment;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.log4j.LogManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.dag.api.DAG;
import com.dag.api.Edge;
import com.dag.api.Vertex;
import com.runtime.api.EsperKafkaProcessor;
import com.yarn.esper.conf.EsperConfiguration;

public class EsperApplicationMaster {
	
	private static final Log LOG = LogFactory.getLog(EsperApplicationMaster.class);
	
	// Configuration
	private Configuration conf;
	
	// Handle to communicate with the Resource Manager
	private AMRMClientAsync<ContainerRequest> amRMClient;
	
	// Handle to communicate with the Node Manager
	private NMClientAsync nmClient;
	
	// Listen to process the response from the Resource Manager
	private ContainerAllocator containerAllocator;
	
	// Listen to process the response from the Node Manager
	private ContainerListener containerListener;
	
	// Application Attempt Id ( combination of attemptId and fail count )
	private ApplicationAttemptId appAttemptId;
	
	// For status update for clients - yet to be implemented
	// Hostname of the container
	private String appMasterHostName;
	// Port on which the app master listens for status updates from clients
	private int appMasterRpcPort;
	// Tracking url to which app master publishes info for clients to monitor
	private String appMasterTrackingUrl;
	
	// App Master configuration
	// No. of containers
	private int totalContainers;
	// Memory to request for the container
	private int containerMemory;
	// VirtualCores to request for the container
	private int containerVCores;
	// Priority of the request
	private int requestPriority;
	
	private String esperEngineJarPath;
	private String esperEngineMainClass;
	
	private String kafkaServer;
	
	// Counter for completed containers ( complete denotes successful or failed )
	private AtomicInteger completedContainers;
	// Allocated container count so that we know how many containers has the RM allocated to us
	private AtomicInteger allocatedContainers;
	// Count of failed containers
	private AtomicInteger failedContainers;
	// Count of containers already requested from the RM
	// Needed as once requested, we should not request for containers again.
	// Only request for more if the original requirement changes.
	private AtomicInteger requestedContainers;
	
	// Command line options
	private Options opts;
	
	// Launch threads
	private List<Thread> launchThreads;
	
	private String nodes;
	private DAG dag;
	private Queue<Integer> nodesId;
	
	private boolean done;
	
	public EsperApplicationMaster() {
		conf = new YarnConfiguration();
		conf.setStrings(YarnConfiguration.RM_HOSTNAME, "10.109.253.145");
		
		appMasterHostName = "";
		appMasterRpcPort = -1;
		appMasterTrackingUrl = "";
		
		totalContainers = 0;
		containerMemory = 16;
		containerVCores = 1;
		requestPriority = 0;
		
		esperEngineJarPath = "";
		esperEngineMainClass = "";
		
		kafkaServer = "";
		
		completedContainers = new AtomicInteger();
		allocatedContainers = new AtomicInteger();
		failedContainers = new AtomicInteger();
		requestedContainers = new AtomicInteger();
		
		launchThreads = new ArrayList<Thread>();
		
		nodes = "";
		nodesId = new LinkedList<Integer>();
		
		opts = new Options();
	}
	
	public boolean init(String[] args) throws ParseException {
		
		opts.addOption("container_memory", true, "Amount of memory in MB to be requested");
		opts.addOption("container_vcores", true, "Amount of virtual cores to be requested");
		opts.addOption("request_priority", true, "Application Priority. Default 0");
		
		opts.addOption("esper_jar_path", true, "The esper jar path");
		opts.addOption("esper_main_class", true, "The esper main class");
		
		opts.addOption("kafka_server", true, "The kafka server");

		opts.addOption("nodes", true, "The json of the nodes");
		
		CommandLine cliParser = new GnuParser().parse(opts, args);
		
		Map<String, String> sysEnv = System.getenv();
		if (!sysEnv.containsKey(Environment.CONTAINER_ID.name())){
			throw new RuntimeException(ApplicationConstants.APP_SUBMIT_TIME_ENV 
					+ " not set in the environment");
		}else{
			ContainerId containerId = ConverterUtils.toContainerId
					(sysEnv.get(Environment.CONTAINER_ID.name()));
			appAttemptId = containerId.getApplicationAttemptId();
		}
		
		if(!sysEnv.containsKey(ApplicationConstants.APP_SUBMIT_TIME_ENV)){
			throw new RuntimeException(ApplicationConstants.APP_SUBMIT_TIME_ENV 
					+ " not set in the environment");
		}
		
		if(!sysEnv.containsKey(Environment.NM_HOST.name())){
			throw new RuntimeException(Environment.NM_HOST.name() 
					+ " not set in the environment");
		}
		
		if(!sysEnv.containsKey(Environment.NM_HTTP_PORT.name())){
			throw new RuntimeException(Environment.NM_HTTP_PORT.name() 
					+ " not set in the environment");
		}
		
		if(!sysEnv.containsKey(Environment.NM_PORT.name())){
			throw new RuntimeException(Environment.NM_PORT.name() 
					+ " not set in the environment");
		}
		
		LOG.info("Application master for app" + ", appId="
		        + appAttemptId.getApplicationId().getId() + ", clustertimestamp="
		        + appAttemptId.getApplicationId().getClusterTimestamp()
		        + ", attemptId=" + appAttemptId.getAttemptId());
		
		appMasterHostName = NetUtils.getHostname();
		appMasterRpcPort = 0;
		appMasterTrackingUrl = "";
		
		esperEngineJarPath = cliParser.getOptionValue("esper_jar_path", "/usr/esper/apps/esper-kafka-engine.jar");
		esperEngineMainClass = cliParser.getOptionValue("esper_main_class", "com.esper.kafka.adapter.EsperKafkaAdapter");
		
		kafkaServer = cliParser.getOptionValue("kafka_server", "10.109.253.127:9092");
		
		nodes = cliParser.getOptionValue("nodes", "{}")
				.replaceAll("%", "\"").replaceAll("$", "\'");
		LOG.info("get nodes json " + nodes);
		
		containerMemory = Integer.parseInt(cliParser.getOptionValue("container_memory", "16"));
		containerVCores = Integer.parseInt(cliParser.getOptionValue("container_vcores", "1"));
		requestPriority = Integer.parseInt(cliParser.getOptionValue("request_priority", "0"));
		
		dag = convertToDag(nodes);
		
		totalContainers = nodesId.size();
		LOG.info("totalContainer num is " + totalContainers);
		
		containerAllocator = new ContainerAllocator();
		amRMClient = AMRMClientAsync.createAMRMClientAsync(100, containerAllocator);
		
		containerListener = new ContainerListener();
		nmClient = NMClientAsync.createNMClientAsync(containerListener);
		
		return true;
	}
	
	public void runContainers() throws YarnException, IOException {
		
		LOG.info("Starting ApplicationMaster");
		
		amRMClient.init(conf);
		amRMClient.start();
		
		nmClient.init(conf);
		nmClient.start();
		
		// Register self with ResourceManager
	    // This will start heartbeating to the RM
		RegisterApplicationMasterResponse response = amRMClient.registerApplicationMaster
				(appMasterHostName, appMasterRpcPort, appMasterTrackingUrl);
		
		int maxMemory = response.getMaximumResourceCapability().getMemory();
		LOG.info("Max mem capabililty of resources in this cluster " + maxMemory);
		
		int maxVCores = response.getMaximumResourceCapability().getVirtualCores();
		LOG.info("Max vcores capabililty of resources in this cluster " + maxVCores);
		
		if(containerMemory>maxMemory){
			LOG.info("Container memory specified above max threshold of cluster."
			          + " Using max value." + ", specified=" + containerMemory + ", max="
			          + maxMemory);
			containerMemory = maxMemory;
		}
		
		if(containerVCores>maxVCores){
			LOG.info("Container virtual cores specified above max threshold of cluster."
			          + " Using max value." + ", specified=" + containerVCores + ", max="
			          + maxVCores);
			containerVCores = maxVCores;
		}
		
		// Setup ask for containers from RM
	    // Send request for containers to RM
	    // Until we get our fully allocated quota, we keep on polling RM for
	    // containers
	    // Keep looping until all the containers are launched and app
	    // executed on them ( regardless of success/failure).
		requestedContainers.addAndGet(totalContainers);
		for(int i=0;i<totalContainers;i++){
			ContainerRequest containerReuqest = setupContainerAskForRM();
		    amRMClient.addContainerRequest(containerReuqest);
		}
		
	}
	
	//Setup the request that will be sent to the RM for the container ask.
	private ContainerRequest setupContainerAskForRM() {
		
		// set the priority for the request
		Priority pri = Priority.newInstance(requestPriority);
		
		// Set up resource type requirements
		Resource capability = Resource.newInstance(containerMemory, containerVCores);
		ContainerRequest request = new ContainerRequest(capability, null, null, pri);
		
		LOG.info("Requested container ask: " + request.toString());
		return request;
	}
	
	private DAG convertToDag(String json) throws JSONException {
		
		DAG dag = DAG.create("dag");
		
		JSONObject jsonObject = new JSONObject(json);
		JSONArray jsonArray = jsonObject.getJSONArray("nodes");
		
		for(int i=0;i<jsonArray.length();i++){
			JSONObject nodeJson = jsonArray.getJSONObject(i);
			int id = nodeJson.getInt("id");
			nodesId.offer(id);
			
			String name = nodeJson.getString("name");
			Vertex vertex = null;
			if(dag.containsVertex(id)){
				vertex = dag.getVertex(id);
			}else{
				vertex = Vertex.create(id);
				dag.addVertex(vertex);
			}	
			vertex.setVertexName(name);
			JSONArray children = nodeJson.getJSONArray("children");
			for(int j=0;j<children.length();j++){
				int cid = children.getInt(j);
				Vertex child = null;
				if(dag.containsVertex(cid)){
					child = dag.getVertex(cid);
				}else{
					child = Vertex.create(cid);
					dag.addVertex(child);
				}
				Edge edge = Edge.create(vertex, child);
				dag.addEdge(edge);
			}
			
			EsperKafkaProcessor processor = new EsperKafkaProcessor();
			JSONArray eventTypes = nodeJson.getJSONArray("event_types");
			processor.setEventType(eventTypes.toString());
			
			JSONArray epls = nodeJson.getJSONArray("epl");
			processor.setEpl(epls.toString());
			
			processor.setOutType(nodeJson.getString("out_type"));
			processor.setParallelism(nodeJson.getInt("num"));
			
			vertex.setProcessor(processor);
			
		}
		
		return dag;
		
	}
	
	public boolean finish() throws YarnException, IOException {
		
		// wait for completion.
		while(!done && (completedContainers.get()!=totalContainers)){
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		// Join all launched threads
	    // needed for when we time out
	    // and we need to release containers
		for(Thread launchThread : launchThreads){
			try {
				launchThread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				LOG.info("Exception thrown in thread join: " + e.getMessage());
				e.printStackTrace();
			}
		}
		
		// When the application completes, it should stop all running containers
		LOG.info("Application completed. Stopping running containers");
		nmClient.stop();
		
		// When the application completes, it should send a finish application
	    // signal to the RM
		LOG.info("Application completed. Signalling finish to RM");
		
		FinalApplicationStatus appStatus = FinalApplicationStatus.SUCCEEDED;
		String appMessage = "";
		boolean success = true;
		
		if(failedContainers.get()!=0){
			appStatus = FinalApplicationStatus.FAILED;
			appMessage = "total: " + totalContainers 
					+ ", completed: " + completedContainers.get()
					+ ", allocated: " + allocatedContainers.get()
					+ ", failed: " + failedContainers.get();
			LOG.info(appMessage);
			success = false;
		}
		
		try {
			amRMClient.unregisterApplicationMaster(appStatus, appMessage, "");
		} catch (YarnException ex) {
			// TODO: handle exception
			LOG.error("Failed to unregister application", ex);
		} catch (IOException e) {
			LOG.error("Failed to unregister application", e);
		}
		
		amRMClient.stop();
		
		return success;
	}
	
	private class ContainerAllocator implements AMRMClientAsync.CallbackHandler {

		@Override
		public float getProgress() {
			// TODO Auto-generated method stub
			// set progress to deliver to RM on next heartbeat
			return (float) completedContainers.get()/totalContainers;
		}

		@Override
		public void onContainersAllocated(List<Container> containers) {
			// TODO Auto-generated method stub
			LOG.info("Got response from RM for container ask, allocatedCnt="
			          + containers.size());
			
			allocatedContainers.addAndGet(containers.size());
			for(Container container : containers){
				LOG.info("Launching app on a new container."
			            + ", containerId=" + container.getId()
			            + ", containerNode=" + container.getNodeId().getHost()
			            + ":" + container.getNodeId().getPort()
			            + ", containerNodeURI=" + container.getNodeHttpAddress()
			            + ", containerResourceMemory"
			            + container.getResource().getMemory()
			            + ", containerResourceVirtualCores"
			            + container.getResource().getVirtualCores());
				
				int id = 0;
				if(!nodesId.isEmpty()){
					id = nodesId.poll();
					LOG.info("add node of id " + id + " to container");
					Thread launchThread = new Thread(new LaunchContainerRunnable(container, id));
					
					// launch and start the container on a separate thread to keep
			        // the main thread unblocked
			        // as all containers may not be allocated at one go.
					launchThreads.add(launchThread);
					launchThread.start();
				}
				
			}
		}

		@Override
		public void onContainersCompleted(List<ContainerStatus> containerStatuses) {
			// TODO Auto-generated method stub
			LOG.info("Got response from RM for container ask, completedCnt="
			          + containerStatuses.size());
			
			for(ContainerStatus containerStatus : containerStatuses){
				LOG.info(appAttemptId + " got container status for containerID="
			            + containerStatus.getContainerId() + ", state="
			            + containerStatus.getState() + ", exitStatus="
			            + containerStatus.getExitStatus() + ", diagnostics="
			            + containerStatus.getDiagnostics());
				
				int exitStatus = containerStatus.getExitStatus();
				if(exitStatus!=ContainerExitStatus.SUCCESS){
					if(exitStatus!=ContainerExitStatus.ABORTED){
						completedContainers.incrementAndGet();
						failedContainers.incrementAndGet();
					}else{
						allocatedContainers.decrementAndGet();
						requestedContainers.decrementAndGet();
					}
				}else{
					LOG.info("Container completed successfully." + ", containerId="
				              + containerStatus.getContainerId());
					completedContainers.incrementAndGet();
				}
			}
			
			int numToRequest = totalContainers - requestedContainers.get();
			requestedContainers.addAndGet(numToRequest);
			for(int i=0;i<numToRequest;i++){
				ContainerRequest containerReuqest = setupContainerAskForRM();
				amRMClient.addContainerRequest(containerReuqest);
			}
			
			if(completedContainers.get()==totalContainers)done = true;
		}

		@Override
		public void onError(Throwable arg0) {
			// TODO Auto-generated method stub
			done = true;
			amRMClient.stop();
		}

		@Override
		public void onNodesUpdated(List<NodeReport> arg0) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void onShutdownRequest() {
			// TODO Auto-generated method stub
			done = true;
		}
		
	}
	
	private class ContainerListener implements NMClientAsync.CallbackHandler {

		@Override
		public void onContainerStarted(ContainerId containerId, Map<String, ByteBuffer> arg1) {
			// TODO Auto-generated method stub
			
			LOG.info("Succeeded to start Container " + containerId);
			
		}

		@Override
		public void onContainerStatusReceived(ContainerId containerId, ContainerStatus containerStatus) {
			// TODO Auto-generated method stub
			
			LOG.info("Container Status: id=" + containerId + ", status=" +
		            containerStatus);
			
		}

		@Override
		public void onContainerStopped(ContainerId containerId) {
			// TODO Auto-generated method stub
			
			LOG.info("Succeeded to stop Container " + containerId);
			
		}

		@Override
		public void onGetContainerStatusError(ContainerId containerId, Throwable arg1) {
			// TODO Auto-generated method stub
			
			LOG.error("Failed to query the status of Container " + containerId);
			
		}

		@Override
		public void onStartContainerError(ContainerId containerId, Throwable arg1) {
			// TODO Auto-generated method stub
			
			LOG.error("Failed to start Container " + containerId);
			completedContainers.incrementAndGet();
			failedContainers.incrementAndGet();
		}

		@Override
		public void onStopContainerError(ContainerId containerId, Throwable arg1) {
			// TODO Auto-generated method stub
			
			LOG.error("Failed to stop Container " + containerId);
			
		}
		
	}
	
	private class LaunchContainerRunnable implements Runnable {
		
		// Allocated container
		private Container container;
		private int nodeId;
		
		public LaunchContainerRunnable(Container container, int nodeId) {
			this.container = container;
			this.nodeId = nodeId;
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub
			
			LOG.info("Setting up container launch container for containerid="
			          + container.getId());
			
			// Set the local resources
			/*Map<String, LocalResource> localResources = new HashMap<String, LocalResource>();
			try {
				FileSystem fs = FileSystem.get(conf);
				Path esperJarPath = new Path(esperEngineJarPath);
				FileStatus jarStatus = fs.getFileStatus(esperJarPath);
				LocalResource esperJar = Records.newRecord(LocalResource.class);
				esperJar.setResource(ConverterUtils.getYarnUrlFromPath(esperJarPath));
				esperJar.setSize(jarStatus.getLen());
				esperJar.setTimestamp(jarStatus.getModificationTime());
				esperJar.setType(LocalResourceType.FILE);
				esperJar.setVisibility(LocalResourceVisibility.PUBLIC);
				localResources.put("esperJar", esperJar);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				LOG.error("Read esper jar file error", e);
				completedContainers.incrementAndGet();
				failedContainers.incrementAndGet();
			}*/

			// Set the environment
			Map<String, String> esperEnv = new HashMap<String, String>();
			StringBuilder esperClasspath = new StringBuilder(Environment.CLASSPATH.$());
			esperClasspath.append(ApplicationConstants.CLASS_PATH_SEPARATOR).append("./*");
			for(String c : EsperConfiguration.DEFAULT_ESPER_APPLICATION_CLASSPARH){
				esperClasspath.append(ApplicationConstants.CLASS_PATH_SEPARATOR);
				esperClasspath.append(c.trim());
			}
			esperClasspath.append(ApplicationConstants.CLASS_PATH_SEPARATOR);
			esperClasspath.append(esperEngineJarPath);
			esperEnv.put("CLASSPATH", esperClasspath.toString());
			
			LOG.info("Complete setting up esper env " + esperClasspath.toString());
			
			Vertex v = dag.getVertex(nodeId);
			String eventType = "\'" + v.getProcessor().getEventTypes()
					.replace("\"", "%")+ "\'";
			String epl = "\'" + v.getProcessor().getEpls().toString()
					.replace("\"", "%").replace("\'", "$")+ "\'";
			String outType = v.getProcessor().getOutType();
			String nodeName = v.getVertexName();
			StringBuilder p = new StringBuilder();
			p.append("\'[");
			
			for(Edge edge : v.getInputEdges()){
				p.append("\"").append(edge.getInputVertex().getVertexName())
				.append("\"").append(",");
			}
			if(v.getInputEdges().size()==0)p.append("\"node0\"");
			p.append("]\'");
			
			String parents = p.toString().replace("\"", "%");
			
			// Set the necessary command to execute on the allocated container
			List<String> esperCommands = new ArrayList<String>();
			esperCommands.add("$JAVA_HOME/bin/java");
			esperCommands.add("-Xmx" + containerMemory + "M");
			esperCommands.add(esperEngineMainClass);
			// Set params for Application Master
			esperCommands.add("--kafka_server " + kafkaServer);
			esperCommands.add("--event_type " + eventType);
			esperCommands.add("--epl " + epl);
			esperCommands.add("--out_type " + outType);
			esperCommands.add("--parents " + parents);
			esperCommands.add("--node_name " + nodeName);
			//esperCommands.add("mkdir /usr/test");
			
			LOG.info("Completed setting up esper engine command " + esperCommands.toString());
			
			// Set up ContainerLaunchContext, setting local resource, environment, command
			ContainerLaunchContext esperContainer = ContainerLaunchContext.newInstance
					(null, esperEnv, esperCommands, null, null, null);
						
			
			nmClient.startContainerAsync(container, esperContainer);
		}
		
	}
	
	public static void main(String[] args) throws YarnException, IOException {
		
		boolean result = false;
		
		try {
			EsperApplicationMaster appMaster = new EsperApplicationMaster();
			LOG.info("Initializing ApplicationMaster");
			boolean doRun = appMaster.init(args);
			if(!doRun){
				System.exit(0);
			}
			appMaster.runContainers();
			result = appMaster.finish();
		} catch (Throwable t) {
			// TODO: handle exception
			LOG.fatal("Error running ApplicationMaster", t);
			LogManager.shutdown();
		    ExitUtil.terminate(1, t);
		}
		
		if(result){
			LOG.info("Application Master completed successfully. exiting");
			System.exit(0);
		}
		
		LOG.info("Application Master failed. exiting");
		System.exit(2);
		
	}

}
