# HiveMQ DCOS Service

Requires [dcosdev](https://github.com/mesosphere/dcosdev) for development

## Rolling Upgrade

A rolling upgrade has to be performed in 2 steps:

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

### VIP

This service binds HiveMQ's control center to a random port and exposes the address as a DNS record for each cluster node.
TODO: how to get MLB to forward requests to one or more of the pod instances?

## TLS

TODO