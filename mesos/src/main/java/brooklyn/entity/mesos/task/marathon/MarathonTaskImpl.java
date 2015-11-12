/*
 * Copyright 2014-2015 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brooklyn.entity.mesos.task.marathon;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.python.google.common.base.Splitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.net.HttpHeaders;
import com.google.common.primitives.Ints;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import org.jclouds.compute.domain.Processor;
import org.jclouds.compute.domain.TemplateBuilder;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.location.PortRange;
import org.apache.brooklyn.api.mgmt.LocationManager;
import org.apache.brooklyn.core.config.render.RendererHints;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.feed.ConfigToAttributes;
import org.apache.brooklyn.core.location.LocationConfigKeys;
import org.apache.brooklyn.core.location.cloud.CloudLocationConfig;
import org.apache.brooklyn.core.location.dynamic.DynamicLocation;
import org.apache.brooklyn.core.sensor.DependentConfiguration;
import org.apache.brooklyn.core.sensor.PortAttributeSensorAndConfigKey;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.stock.DelegateEntity;
import org.apache.brooklyn.feed.http.HttpFeed;
import org.apache.brooklyn.feed.http.HttpPollConfig;
import org.apache.brooklyn.feed.http.HttpValueFunctions;
import org.apache.brooklyn.feed.http.JsonFunctions;
import org.apache.brooklyn.location.jclouds.JcloudsLocationConfig;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.core.http.HttpTool;
import org.apache.brooklyn.util.core.http.HttpToolResponse;
import org.apache.brooklyn.util.core.internal.ssh.SshTool;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.guava.Functionals;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.net.Urls;
import org.apache.brooklyn.util.time.Time;

import brooklyn.entity.container.DockerAttributes;
import brooklyn.entity.container.DockerUtils;
import brooklyn.entity.container.docker.DockerContainer;
import brooklyn.entity.mesos.framework.MesosFramework;
import brooklyn.entity.mesos.framework.marathon.MarathonFramework;
import brooklyn.entity.mesos.task.MesosTask;
import brooklyn.entity.mesos.task.MesosTaskImpl;
import brooklyn.location.mesos.framework.marathon.MarathonTaskLocation;
import brooklyn.networking.subnet.SubnetTier;

/**
 * A single Marathon task.
 */
public class MarathonTaskImpl extends MesosTaskImpl implements MarathonTask {

    private static final Logger LOG = LoggerFactory.getLogger(MesosTask.class);

    /** Valid characters for a task name. */
    public static final CharMatcher TASK_CHARACTERS = CharMatcher.anyOf("_-/")
            .or(CharMatcher.inRange('a', 'z'))
            .or(CharMatcher.inRange('0', '9'));

    /** Invalid characters for a task name. */
    public static final CharMatcher TASK_CHARACTERS_INVALID = TASK_CHARACTERS.negate();

    private transient HttpFeed httpFeed;

    @Override
    public void init() {
        super.init();

        ConfigToAttributes.apply(this, ENTITY);

        String id = getMarathonApplicationId();
        String name = Joiner.on('.').join(Lists.reverse(Splitter.on('/').omitEmptyStrings().splitToList(id)));
        sensors().set(APPLICATION_ID, id);
        sensors().set(TASK_NAME, name);

        LOG.info("Marathon task {} for: {}", id, sensors().get(ENTITY));
    }

    @Override
    public String getIconUrl() { return "classpath://container.png"; }

    private String getMarathonApplicationId() {
        String id = "/brooklyn/" + config().get(TASK_NAME).toLowerCase(Locale.ENGLISH);
        return TASK_CHARACTERS_INVALID.trimAndCollapseFrom(id, '_');
    }

    @Override
    public void connectSensors() {
        String uri = Urls.mergePaths(getFramework().sensors().get(MarathonFramework.FRAMEWORK_URL), "/v2/apps", sensors().get(APPLICATION_ID), "tasks");
        HttpFeed.Builder httpFeedBuilder = HttpFeed.builder()
                .entity(this)
                .period(500, TimeUnit.MILLISECONDS)
                .baseUri(uri)
                .header("Accept", "application/json")
                .poll(new HttpPollConfig<Boolean>(SERVICE_UP)
                        .onSuccess(Functionals.chain(HttpValueFunctions.jsonContents(), JsonFunctions.walk("tasks"),
                                new Function<JsonElement, Boolean>() {
                                    @Override
                                    public Boolean apply(JsonElement input) {
                                        JsonArray tasks = input.getAsJsonArray();
                                        return tasks.size() == 1;
                                    }
                                }))
                        .onFailureOrException(Functions.constant(Boolean.FALSE)))
                .poll(new HttpPollConfig<Long>(TASK_STARTED_AT)
                        .onSuccess(Functionals.chain(HttpValueFunctions.jsonContents(), JsonFunctions.walk("tasks"),
                                new Function<JsonElement, Long>() {
                                    @Override
                                    public Long apply(JsonElement input) {
                                        JsonArray tasks = input.getAsJsonArray();
                                        for (JsonElement each : tasks) {
                                            JsonElement startedAt = each.getAsJsonObject().get("startedAt");
                                            if (startedAt != null && !startedAt.isJsonNull()) {
                                                return Time.parseDate(startedAt.getAsString()).getTime();
                                            }
                                        }
                                        return null;
                                    }
                                }))
                        .onFailureOrException(Functions.<Long>constant(-1L)))
                .poll(new HttpPollConfig<Long>(TASK_STAGED_AT)
                        .onSuccess(Functionals.chain(HttpValueFunctions.jsonContents(), JsonFunctions.walk("tasks"),
                                new Function<JsonElement, Long>() {
                                    @Override
                                    public Long apply(JsonElement input) {
                                        JsonArray tasks = input.getAsJsonArray();
                                        for (JsonElement each : tasks) {
                                            JsonElement stagedAt = each.getAsJsonObject().get("stagedAt");
                                            if (stagedAt != null && !stagedAt.isJsonNull()) {
                                                return Time.parseDate(stagedAt.getAsString()).getTime();
                                            }
                                        }
                                        return null;
                                    }
                                }))
                        .onFailureOrException(Functions.<Long>constant(-1L)))
                .poll(new HttpPollConfig<String>(Attributes.HOSTNAME)
                        .onSuccess(Functionals.chain(HttpValueFunctions.jsonContents(), JsonFunctions.walk("tasks"),
                                new Function<JsonElement, String>() {
                                    @Override
                                    public String apply(JsonElement input) {
                                        JsonArray tasks = input.getAsJsonArray();
                                        for (JsonElement each : tasks) {
                                            JsonElement host = each.getAsJsonObject().get("host");
                                            if (host != null && !host.isJsonNull()) {
                                                return host.getAsString();
                                            }
                                        }
                                        return null;
                                    }
                                }))
                        .onFailureOrException(Functions.<String>constant(null)))
                .poll(new HttpPollConfig<String>(Attributes.ADDRESS)
                        .onSuccess(Functionals.chain(HttpValueFunctions.jsonContents(), JsonFunctions.walk("tasks"),
                                new Function<JsonElement, String>() {
                                    @Override
                                    public String apply(JsonElement input) {
                                        JsonArray tasks = input.getAsJsonArray();
                                        for (JsonElement each : tasks) {
                                            JsonElement host = each.getAsJsonObject().get("host");
                                            if (host != null && !host.isJsonNull()) {
                                                try {
                                                    return InetAddress.getByName(host.getAsString()).getHostAddress();
                                                } catch (UnknownHostException uhe) {
                                                    Exceptions.propagate(uhe);
                                                }
                                            }
                                        }
                                        return null;
                                    }
                                }))
                        .onFailureOrException(Functions.<String>constant(null)));
        httpFeed = httpFeedBuilder.build();
    }

    @Override
    public void disconnectSensors() {
        if (httpFeed != null && httpFeed.isRunning()) httpFeed.stop();
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        super.start(locations);

        ServiceStateLogic.setExpectedState(this, Lifecycle.STARTING);

        Entity entity = getRunningEntity();
        MarathonFramework marathon = (MarathonFramework) sensors().get(FRAMEWORK);
        marathon.sensors().get(MesosFramework.FRAMEWORK_TASKS).addMember(this);

        String id = sensors().get(APPLICATION_ID);
        Map<String, Object> flags = getMarathonFlags(entity);
        try {
            LOG.debug("Starting task {} on {} with flags: {}",
                    new Object[] { id, marathon, Joiner.on(",").withKeyValueSeparator("=").join(flags) });
            marathon.startApplication(id, flags);
        } catch (Exception e) {
            ServiceStateLogic.setExpectedState(this, Lifecycle.ON_FIRE);
            Exceptions.propagate(e);
        }

        String hostname = DependentConfiguration.waitInTaskForAttributeReady(this, Attributes.HOSTNAME, Predicates.notNull());
        LOG.info("Task {} running on {} successfully", id, hostname);

        createLocation(flags);

        ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
    }

    @Override
    public void stop() {
        Lifecycle state = sensors().get(Attributes.SERVICE_STATE_ACTUAL);
        if (Lifecycle.STOPPING.equals(state) || Lifecycle.STOPPED.equals(state)) {
            LOG.debug("Ignoring request to stop {} when it is already {}", this, state);
            LOG.trace("Duplicate stop came from: \n" + Joiner.on("\n").join(Thread.getAllStackTraces().get(Thread.currentThread())));
            return;
        }
        LOG.info("Stopping {} when its state is {}", this, sensors().get(Attributes.SERVICE_STATE_ACTUAL));
        ServiceStateLogic.setExpectedState(this, Lifecycle.STOPPING);

        deleteLocation();

        // Stop and remove the Marathon task
        String name = sensors().get(APPLICATION_ID);
        ((MarathonFramework) getFramework()).stopApplication(name);

        super.stop();
    }

    private Map<String, Object> getMarathonFlags(Entity entity) {
        MutableMap.Builder<String, Object> builder = MutableMap.builder();
        Map<String, Object> provisioningProperties = ImmutableMap.copyOf(entity.config().get(SoftwareProcess.PROVISIONING_PROPERTIES));

        // CPU
        Double cpus = entity.config().get(MarathonTask.CPU_RESOURCES);
        if (cpus == null) cpus = config().get(MarathonTask.CPU_RESOURCES);
        if (cpus == null) {
            Integer minCores = entity.config().get(JcloudsLocationConfig.MIN_CORES);
            if (minCores == null) {
                minCores = (Integer) provisioningProperties.get(JcloudsLocationConfig.MIN_CORES.getName());
            }
            if (minCores == null) {
                TemplateBuilder template = (TemplateBuilder) provisioningProperties.get(JcloudsLocationConfig.TEMPLATE_BUILDER.getName());
                if (template != null) {
                    minCores = 0;
                    for (Processor cpu : template.build().getHardware().getProcessors()) {
                        minCores = minCores + (int) cpu.getCores();
                    }
                }
            }
            if (minCores != null) {
                cpus = 1.0d * minCores;
            }
        }
        if (cpus == null) cpus = 0.25d;
        builder.put("cpus", cpus);

        // Memory
        Integer memory = entity.config().get(MarathonTask.MEMORY_RESOURCES);
        if (memory == null) memory = config().get(MarathonTask.MEMORY_RESOURCES);
        if (memory == null) {
            Integer minRam = (Integer) entity.config().get(JcloudsLocationConfig.MIN_RAM);
            if (minRam == null) {
                minRam = (Integer) provisioningProperties.get(JcloudsLocationConfig.MIN_RAM.getName());
            }
            if (minRam == null) {
                TemplateBuilder template = (TemplateBuilder) provisioningProperties.get(JcloudsLocationConfig.TEMPLATE_BUILDER.getName());
                if (template != null) {
                    minRam = template.build().getHardware().getRam();
                }
            }
            if (minRam != null) {
                memory = minRam;
            }
        }
        if (memory == null) memory = 256;
        builder.put("memory", memory);

        // Inbound ports
        Set<Integer> entityOpenPorts = MutableSet.copyOf(DockerUtils.getContainerPorts(entity));
        entityOpenPorts.addAll(DockerUtils.getOpenPorts(entity));
        if (!config().get(DockerContainer.DOCKER_USE_SSH)) {
            entityOpenPorts.remove(22);
        }
        builder.put("openPorts", Ints.toArray(entityOpenPorts));
        sensors().set(DockerAttributes.DOCKER_CONTAINER_OPEN_PORTS, ImmutableList.copyOf(entityOpenPorts));
        entity.sensors().set(DockerAttributes.DOCKER_CONTAINER_OPEN_PORTS, ImmutableList.copyOf(entityOpenPorts));

        // Direct port mappings
        // Note that the Marathon map is reversed, from container to host, with 0 indicating any host port
        Map<Integer, Integer> bindings = MutableMap.of();
        Map<Integer, Integer> marathonBindings = MutableMap.of();
        for (Integer port : entityOpenPorts) {
            marathonBindings.put(port, 0);
        }
        Map<Integer, Integer> entityBindings = entity.config().get(DockerAttributes.DOCKER_PORT_BINDINGS);
        if (entityBindings != null) {
            for (Integer host : entityBindings.keySet()) {
                bindings.put(entityBindings.get(host), host);
                marathonBindings.put(host, entityBindings.get(host));
            }
        }
        if (bindings.isEmpty()) {
            List<PortAttributeSensorAndConfigKey> entityPortConfig = entity.config().get(DockerAttributes.DOCKER_DIRECT_PORT_CONFIG);
            if (entityPortConfig != null) {
                for (PortAttributeSensorAndConfigKey key : entityPortConfig) {
                    PortRange range = entity.config().get(key);
                    if (range != null && !range.isEmpty()) {
                        Integer port = range.iterator().next();
                        if (port != null) {
                            bindings.put(port,  port);
                            marathonBindings.put(port,  port);
                        }
                    }
                }
            }
            List<Integer> entityPorts = entity.config().get(DockerAttributes.DOCKER_DIRECT_PORTS);
            if (entityPorts != null) {
                for (Integer port : entityPorts) {
                    bindings.put(port, port);
                    marathonBindings.put(port,  port);
                }
            }
        }
        sensors().set(DockerAttributes.DOCKER_CONTAINER_PORT_BINDINGS, bindings);
        entity.sensors().set(DockerAttributes.DOCKER_CONTAINER_PORT_BINDINGS, bindings);
        builder.put("portBindings", Lists.newArrayList(marathonBindings.entrySet()));

        // Environment variables
        Map<String, Object> environment = MutableMap.copyOf(config().get(DOCKER_CONTAINER_ENVIRONMENT));
        builder.put("environment", Lists.newArrayList(environment.entrySet()));

        Optional<String> imageName = Optional.fromNullable(config().get(DOCKER_IMAGE_NAME));
        if (imageName.isPresent()) {
            // Docker image
            builder.put("imageName", imageName.get());
            builder.put("imageVersion", config().get(DOCKER_IMAGE_TAG));

            // Docker command or args
            String command = config().get(COMMAND);
            builder.putIfNotNull("command", command);
            List<String> args = config().get(ARGS);
            builder.putIfNotNull("args", args);
        } else {
            // TODO handle SoftwareProcess entities
        }

        return builder.build();
    }

    @Override
    public Entity getRunningEntity() {
        return sensors().get(ENTITY);
    }

    @Override
    public void setRunningEntity(Entity entity) {
        sensors().set(ENTITY, entity);
    }

    @Override
    public String getHostname() {
        return sensors().get(Attributes.HOSTNAME);
    }

    @Override
    public Set<String> getPublicAddresses() {
        return ImmutableSet.of(sensors().get(Attributes.ADDRESS));
    }

    @Override
    public Set<String> getPrivateAddresses() {
        return ImmutableSet.of(sensors().get(Attributes.ADDRESS));
    }

    @Override
    public MarathonTaskLocation getDynamicLocation() {
        return (MarathonTaskLocation) sensors().get(DYNAMIC_LOCATION);
    }

    public Optional<JsonElement> getApplicationJson() {
        String uri = Urls.mergePaths(getFramework().sensors().get(MarathonFramework.FRAMEWORK_URL), "/v2/apps", sensors().get(APPLICATION_ID));
        HttpToolResponse response = HttpTool.httpGet(
                HttpTool.httpClientBuilder().uri(uri).build(),
                URI.create(uri),
                MutableMap.of(HttpHeaders.ACCEPT, "application/json"));
        if (!HttpTool.isStatusCodeHealthy(response.getResponseCode())) {
            return Optional.absent();
        } else {
            LOG.debug("Application JSON: {}", response.getContentAsString());
            return Optional.of(HttpValueFunctions.jsonContents().apply(response));
        }
    }

    @Override
    public MarathonTaskLocation createLocation(Map<String, ?> flags) {
        // Lookup mapped ports
        List<Map.Entry<Integer, Integer>> portBindings = (List) flags.get("portBindings");
        Map<Integer, String> tcpMappings = MutableMap.of();
        Optional<JsonElement> application = getApplicationJson();
        if (application.isPresent()) {
            JsonArray tasks = application.get()
                    .getAsJsonObject().get("app")
                    .getAsJsonObject().get("tasks")
                    .getAsJsonArray();
            for (JsonElement each : tasks) {
                JsonElement ports = each.getAsJsonObject().get("ports");
                if (ports != null && !ports.isJsonNull()) {
                    JsonArray array = ports.getAsJsonArray();
                    if (array.size() > 0) {
                        for (int i = 0; i < array.size(); i++) {
                            int hostPort = array.get(i).getAsInt();
                            int containerPort = portBindings.get(i).getKey();
                            tcpMappings.put(containerPort, getHostname() + ":" + hostPort);
                        }
                    }
                }
            }
        } else {
            throw new IllegalStateException("Cannot retrieve application details for " + sensors().get(APPLICATION_ID));
        }

        // Configure the entity subnet
        SubnetTier subnet = getMarathonFramework().sensors().get(MarathonFramework.MARATHON_SUBNET_TIER);
        Entity entity = getRunningEntity();
        LOG.info("Configuring entity {} via subnet {}", entity, subnet);
        entity.config().set(SubnetTier.PORT_FORWARDING_MANAGER, subnet.getPortForwardManager());
        entity.config().set(SubnetTier.PORT_FORWARDER, subnet.getPortForwarder());
        entity.config().set(SubnetTier.SUBNET_CIDR, Cidr.UNIVERSAL);
        DockerUtils.configureEnrichers(subnet, entity);
        for (int port : (int[]) flags.get("openPorts")) {
            String name = String.format("docker.port.%d", port);
            entity.sensors().set(Sensors.newIntegerSensor(name), port);
        }

        // Create our wrapper location around the task
        LocationSpec<MarathonTaskLocation> spec = LocationSpec.create(MarathonTaskLocation.class)
                .parent(getMarathonFramework().getDynamicLocation())
                .configure(flags)
                .configure(DynamicLocation.OWNER, this)
                .configure("entity", getRunningEntity())
                .configure(CloudLocationConfig.WAIT_FOR_SSHABLE, "false")
                .configure(SshMachineLocation.DETECT_MACHINE_DETAILS, false)
                .configure(SshMachineLocation.TCP_PORT_MAPPINGS, tcpMappings)
                .displayName(getShortName());
        if (config().get(DockerAttributes.DOCKER_USE_SSH)) {
            spec.configure(SshMachineLocation.SSH_HOST, getHostname())
//                .configure(SshMachineLocation.SSH_PORT, getSshPort())
                .configure(LocationConfigKeys.USER, "root")
                .configure(LocationConfigKeys.PASSWORD, "p4ssw0rd")
                .configure(SshTool.PROP_PASSWORD, "p4ssw0rd")
                .configure(LocationConfigKeys.PRIVATE_KEY_DATA, (String) null) // TODO used to generate authorized_keys
                .configure(LocationConfigKeys.PRIVATE_KEY_FILE, (String) null);
        }
        MarathonTaskLocation location = getManagementContext().getLocationManager().createLocation(spec);

        sensors().set(DYNAMIC_LOCATION, location);
        sensors().set(LOCATION_NAME, location.getId());

        LOG.info("New task location {} created", location);
        return location;
    }

    @Override
    public boolean isLocationAvailable() {
        return true;
    }

    @Override
    public void deleteLocation() {
        MarathonTaskLocation location = getDynamicLocation();

        if (location != null) {
            LocationManager mgr = getManagementContext().getLocationManager();
            if (mgr.isManaged(location)) {
                mgr.unmanage(location);
            }
        }

        sensors().set(DYNAMIC_LOCATION, null);
        sensors().set(LOCATION_NAME, null);
    }

    @Override
    public MarathonFramework getMarathonFramework() {
        return (MarathonFramework) sensors().get(FRAMEWORK);
    }

    static {
        RendererHints.register(ENTITY, RendererHints.namedActionWithUrl("Open", DelegateEntity.EntityUrl.entityUrl()));
        RendererHints.register(TASK_STARTED_AT, RendererHints.displayValue(Time.toDateString()));
        RendererHints.register(TASK_STAGED_AT, RendererHints.displayValue(Time.toDateString()));
    }

}
