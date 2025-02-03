# rad

The **r**epository **a**ggregation **d**aemon is an aggregating dependency proxy written in Kotlin/Native using Ktor.  
It was built for GitLab CE to make up for the lack of group- and instance-endpoints for maven repositories.  
It also offers a binary proxy to allow accessing regular build artifacts by version.

**It is strongly recommended to use this service in conjunction with Apache2 or Nginx, since the Ktor CIO server
implementation does not support SSL.**

### Usage

```shell
rad --config rad.json
```

You can also run

```shell
rad --help
```

to get a list of all available commands.

### Endpoints

* **/maven** - Access to all aggregated maven repositories from all package registries
* **/binaries**
  * **/binaries/&lt;version&gt;/&lt;file&gt;** - Acccess to all artifacts from &lt;version&gt;
  * **/binaries/latest/&lt;file&gt;** - Access to all artifacts from the latest release.

### Example config

```json
{
    "instance": "gitlab.com",
    "group": [
        "mygroup1",
        "mygroup2"
    ],
    "poll_delay": 10000
}
```