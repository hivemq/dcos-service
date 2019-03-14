# HiveMQ DCOS Service

Requires [dcosdev](https://github.com/mesosphere/dcosdev) for development

## Accessing the Control Center

### DNS name

This service binds HiveMQ's control center to a random port and exposes the address as a DNS record for each cluster node.

You can use these nodes to create a proxy on a public node to forward requests to the broker using those SRV records.

TODO: how to get MLB to forward requests to one or more of the pod instances?

### Proxy

You can also use the `dcos` CLI's proxy feature to connect to the control center of a single broker directly:

```
mesos_id=$(dcos task hivemq --json | jq -r '.[] | select (.name == "hivemq-0-node").slave_id')
port=$(dcos task hivemq --json  | jq '.[].discovery | select(.name == "hivemq-0") | .ports[] | .[] | select(.name == "control-center").number')
dcos node ssh --master-proxy --mesos-id=$mesos_id --option LocalForward=$port=localhost:$port
```

This will forward the control center port (will be displayed when the SSH tunnel is established) to localhost, allowing you to access the control center of a single HiveMQ node.

## Managing the cluster

Sidecar plans are provided to allow you to maintain the cluster at runtime, performing several common tasks.

### Install a plugin

To install a plugin, you can use the `add-plugin` plan. This plan requires a single parameter `URL` which requires a path to a `.zip` compressed extension folder.

For example, to install the File RBAC Extension on each current cluster node, run:

```
dcos hivemq plan start -p URL=https://www.hivemq.com/releases/extensions/hivemq-file-rbac-extension-4.0.0.zip add-plugin
```

When adding new cluster nodes, you will have to execute this plan again, specifying a `INDEX` parameter specifying which node the plugin should be installed on.
TODO testen und beschreiben

### Adding a license

If you did not specify a license at installation or want to add a new license to the cluster, you can use the `add-license` plan. This plan requires two parameters:

* `LICENSE_NAME`: Name for the license file
* `LICENSE`: The actual encoded license file. Use `cat my_license.lic | base64` to encode a license file.

### Adding plugin configuration

To add arbitrary configuration files for your custom extensions or update existing configurations, you can use the `add-config` plan.
TODO ist es wirklich im extensions ordner oder doch im conf ordner??

## TLS

TODO