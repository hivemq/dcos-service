# HiveMQ DCOS Service

Requires [dcosdev](https://github.com/mesosphere/dcosdev) for development

## Accessing the Control Center

### DNS name

This service binds HiveMQ's control center to a random port and exposes the address as a DNS record for each cluster node.

You can use these records to create a proxy on a public node to forward requests to the broker using those SRV records. (Implement sticky session on the proxy when doing so)

TODO: ELB with sticky session

### DCOS Tunnel

See [Using a DCOS Tunnel](https://docs.mesosphere.com/latest/developing-services/tunnel/) for more information.

Using this approach you can directly use the provided DNS addresses displayed at the `Endpoints` view of your service.

### SSH Proxy

You can also use the `dcos` CLI's proxy feature to connect to the control center of a single broker directly:

```
mesos_id=$(dcos task hivemq --json | jq -r '.[] | select (.name == "hivemq-0-node").slave_id')
port=$(dcos task hivemq --json  | jq '.[].discovery | select(.name == "hivemq-0") | .ports[] | .[] | select(.name == "control-center").number')
dcos node ssh --master-proxy --mesos-id=$mesos_id --option LocalForward=$port=localhost:$port
```

This will forward the control center port (will be displayed when the SSH tunnel is established) to localhost, allowing you to access the control center of a single HiveMQ node.

## Managing the cluster

Sidecar plans are provided to allow you to maintain the cluster at runtime, performing several common tasks.

### Install an extension

To install an extension, you can use the `add-extension` plan. This plan requires a single parameter `URL` which requires a path to a `.zip` compressed extension folder.

For example, to install the File RBAC Extension on each current cluster node, run:

```
dcos hivemq plan start -p URL=https://www.hivemq.com/releases/extensions/hivemq-file-rbac-extension-4.0.0.zip add-plugin
```

When adding new cluster nodes, you will have to execute this plan again, specifying a `INDEX` parameter specifying which node the plugin should be installed on.

TODO index zeug testen und beschreiben ODERplan idempotent machen damit der parameter unnötig it

### Adding a license

If you did not specify a license at installation or want to add a new license to the cluster without triggering a configuration update, you can use the `add-license` plan. This plan requires two parameters:

* `LICENSE_NAME`: Name for the license file
* `LICENSE`: The actual encoded license file. Use `cat my_license.lic | base64` to encode a license file.

### Adding extension configuration

To add arbitrary configuration files for your custom extensions or update existing configurations, you can use the `add-config` plan.

TODO ist es wirklich im extensions ordner oder doch im conf ordner??

## Monitoring

This service supports using DCOS' metrics integration by default. Alternatively you can also install extensions to support your own monitoring solution.

### Setting up a monitoring dashboard

Note: You can also use the alternative guide for Datadog, however we will not provide explicit directions for how to integrate HiveMQ application metrics with Datadog.

1. Follow the steps at [Export DC/OS Metrics to Prometheus](https://docs.mesosphere.com/latest/metrics/prometheus/) to set up Prometheus and Grafana on your DCOS cluster.

2. Open Grafana and add the Prometheus data source.

3. Create a new dashboard by importing the following template

TODO template linken

4. Choose your Prometheus data source

5. Open the dashboard and choose the HiveMQ nodes from the `node` variable

## TLS

TODO