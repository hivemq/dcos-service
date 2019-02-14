# HiveMQ DCOS Service

Requires [dcosdev](https://github.com/mesosphere/dcosdev) for development

## Rolling Upgrade

A rolling upgrade has to be performed in 2 steps (as of now):

1. Update the image, while also incrementing your node count. For example, for a 3 node cluster, in order to update to HiveMQ 4.0.2:

```
echo '{"node": { "count": 4, "image": "hivemq/hivemq4:dcos-4.0.2"}}' | dcos hivemq update start --options=stdin
```

2. After the update completes (can be monitored using `dcos hivemq --name=hivemq update status`), decrement the node count again, to remove the additional update node:

```
echo '{"node": { "count": 3}}' | dcos hivemq update start --options=stdin
```

Alternatively these steps can also be performed using the edit view from the DCOS Web UI.

## Accessing the Control Center

### DNS name

This service binds HiveMQ's control center to a random port and exposes the address as a DNS record for each cluster node.

You can use these nodes to create a proxy on a public node to forward requests to the broker using those SRV records.

TODO: how to get MLB to forward requests to one or more of the pod instances?

### Proxy

You can also use the `dcos` CLI's proxy feature to connect to the control center of a single broker directly:

```
dcos node ssh --master-proxy --mesos-id=<node> --option LocalForward=<remote-port>=localhost:8080
```

where `<node>` is the DCOS agent the broker is running on and `<remote-port>` is the random port assigned to the broker's control center. The port is visible in the service's endpoint list.

## TLS

TODO