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


## TLS

TODO