# rad

The **r**epository **a**ggregation **d**aemon is an aggregating dependency proxy written in Kotlin/Native using Ktor.  
It was built for GitLab CE to make up for the lack of group- and instance-endpoints for maven repositories.

### Usage

```shell
./rad.kexe --config rad.json
```

You can also run

```shell
./rad.kexe --help
```

to get a list of all available commands.

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