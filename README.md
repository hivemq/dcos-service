# HiveMQ DCOS Service

Requires [dcosdev](https://github.com/mesosphere/dcosdev) for development

Note: While providing many functionalities already, this service is still a work in progress.

## Managing the cluster

Sidecar plans are provided to allow you to maintain the cluster at runtime, performing several common tasks.

### Install an extension

To install an extension, you can use the `add-extension` plan. This plan requires a single parameter `URL` which requires a path to a `.zip` compressed extension folder.

For example, to install the File RBAC Extension on each current cluster node, run:

```
dcos hivemq plan start -p URL=https://www.hivemq.com/releases/extensions/hivemq-file-rbac-extension-4.0.0.zip add-plugin
```

When adding new cluster nodes, you will have to execute this plan again, specifying a `INDEX` parameter specifying which node the plugin should be installed on.

### Adding a license

If you did not specify a license at installation or want to add a new license to the cluster without triggering a configuration update, you can use the `add-license` plan. This plan requires two parameters:

* `LICENSE_NAME`: Name for the license file
* `LICENSE`: The actual encoded license file. Use `cat my_license.lic | base64` to encode a license file.

### Adding extension configuration

To add arbitrary configuration files for your custom extensions or update existing configurations, you can use the `add-config` plan.

### Upgrading

To display a list of available package versions for upgrade, run:

```bash
$ dcos hivemq update package-versions
```

This will result in output such as:
```bash
Current package version is: 1.0.1-4.2.0
Package can be downgraded to:
- 1.0.1-4.0.2
No valid package upgrade versions.
```

#### Upgrading or downgrading a service

1. Before updating the service itself, update its CLI subcommand to the new version:

    ```bash
    $ dcos package uninstall --cli hivemq
    $ dcos package install --cli hivemq --package-version="<version>"
    ```

2. After the CLI subcommand has been updated, call the `update start` command, passing in the version

    ```bash
    $ dcos hivemq update start --package-version="<version>"
    ```

    This will result in output such as the following:
    
    ```bash
    $ dcos hivemq update start --package-version="1.0.1-4.2.0"
    Update started. Please use `dcos hivemq --name=hivemq update status` to view progress.
    ``` 

## Monitoring

This service supports using DCOS' metrics integration by default. Alternatively you can also install extensions to support your own monitoring solution.

### Setting up a monitoring dashboard (Grafana)

Note: You can also use the alternative guide for Datadog, however we will not provide explicit directions for how to integrate HiveMQ application metrics with Datadog.

1. Follow the steps at [Export DC/OS Metrics to Prometheus](https://docs.mesosphere.com/latest/metrics/prometheus/) to set up Prometheus and Grafana on your DCOS cluster.

2. Open Grafana and add the Prometheus data source.

3. Create a new dashboard by importing the `HiveMQ-Prometheus.json` file.

4. Choose your Prometheus data source

5. Open the dashboard

## Accessing the Control Center

### DNS name

This service binds HiveMQ's control center to a random port and exposes the address as a DNS record for each cluster node.

You can use these records to create a proxy on a public node to forward requests to the broker using those SRV records. (Implement sticky session on the proxy when doing so)

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

## TLS

Note: To use TLS you need to have an enterprise DC/OS license.

Note: The service will currently use this key pair for all TLS listeners as well as the cluster transport if enabled. 

You need to setup a service account with sufficient privilege to create certificates:

```
dcos package install --cli dcos-enterprise-cli
dcos security org service-accounts keypair private-key.pem public-key.pem
dcos security org service-accounts create -p public-key.pem -d "HiveMQ service account" hivemq-principal
dcos security secrets create-sa-secret --strict private-key.pem hivemq-principal hivemq/account-secret
dcos security org groups add_user superusers hivemq-principal
```

# Acknowledgements

Thank you to MaibornWolff for also providing a HiveMQ [DCOS service prototype](https://github.com/MaibornWolff/dcos-hivemq). Some pieces of code for this service were taken from their implementation as well.