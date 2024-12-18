# Redis-Raft Jepsen Test

This is a test suite, written using the [Jepsen distributed systems testing
library](https://jepsen.io), forked from
[Jepsen test for Redis-Raft](https://github.com/jepsen-io/redis), for [EloqKV](https://www.eloqdata.com/eloqkv/introduction).

## Prerequisites

You'll need a Jepsen cluster running Ubuntu 20, which you can either [build
yourself](https://github.com/jepsen-io/jepsen#setting-up-a-jepsen-environment)
or run in
[AWS](https://aws.amazon.com/marketplace/pp/B01LZ7Y7U0?qid=1486758124485&sr=0-1&ref_=srh_res_product_title)
via Cloudformation.

The control node needs a JVM, Graphviz, and the [Leiningen
build system](https://github.com/technomancy/leiningen#installation). The first
two you can get (on Debian) via:

```
sudo apt install openjdk8-jdk graphviz
```

Jepsen will automatically install dependencies (e.g. git, build tools, various
support libraries) on DB nodes, and clone and build both Redis and Redis-Raft
locally on each DB node (to avoid cross-compilation issues). To do this, root
on each DB node will need an SSH key with access to the Redis-Raft repo on
GitHub, and the GitHub public SSH key in `~/.ssh/known_hosts`---see below.

[Eloqctl](https://www.eloqdata.com/eloqkv/quick-start) is nesessary for deploying EloqKV cluster.
## Usage

### Direct Use
To get started, try
```bash
lein run test-all \
    --node compute-6 \
    --node store-1 \
    --node store-2 \
    --node store-3 \
    --node store-4 \
    --username eloq \
    --password eloq \
    --time-limit 380 \
    --nemesis partition,kill \
    --workload append \
    --nemesis-interval 20 \
    --max-txn-length 4 \
    --test-count 1 \
    --auto-start \
    --internal-nodes store-3,store-4
```

Options details: 
- `node`: The node participating in the Jepsen test, including the transaction  service node, log service node, and storage service node.
- `username and password`: The username and password used for SSH to connect to the nodes listed above.
- `time-limit`: The time (in seconds) for which the test should run.
- `nemesis`: The enabled nemesis. Possible values: none, partition, kill, pause, clock and their combination.
- `workload`: Possible values: append, set, counter. append is recommended.
- `nemesis-interval`: The time interval (in seconds) to trigger a nemesis.
- `max-txn-length`: The number of Redis requests in a multi/exec transaction.
- `test-count`: The maximum number of times to repeat the Jepsen test. If auto-start is disabled, it is recommended to set test-count to 1.
- `auto-start`: Enables automatic startup of the EloqKV cluster during the Jepsen test.
- `internal-nodes`: Specifies the log and storage service nodes, as they do not provide Redis-compatible servers.

### Automation Script
1. Create a `.env` file in the project directory and and add the following lines with the actual value:
    ```
    SENDER_EMAIL=
    PASSWORD=
    RECEIVER_EMAIL=
    ```
    When an error occurs, the `RECEIVER_EMAIL` will receive a notification email.

2. Create a copy of the `jepsen_cmd.sh.template` file and name it `jepsen_cmd.sh` under the `scripts` directory. Keep only one command in the file.

3. Run the Python script to execute jepsen test repeatedly.
    ```bash
    python3 scripts/run_and_check.py 
    ```



## License

Copyright © 2020 Jepsen, LLC

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
