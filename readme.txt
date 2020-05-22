# Raft Key-value Database
<strong>NOTE: Use a Markdown viewer for the best experience</strong>

## Description
This is a key-value database using the Raft distributed algorithm in Java. The
paper for Raft can be found [here](https://raft.github.io/raft.pdf). In this
implementation, JSON files are used as persistent storage.  

All nodes have a *log* and a *state machine*. The log is core to the algorithm
while the state machine is the replicated database in this application.

Clients can connect to any node in the cluster to issue commands for interacting
with the database (e.g. setting a key-value pair or getting the value for a
key). The command received will form a *log entry* within the cluster which is
distributed to other nodes.

Once this log entry has been replicated and committed on a majority of nodes,
its command is applied to the state machine (and thus, applied to the database).

### Consistency
The stored records can be read from any correct node of the Raft cluster at any
time, provided a majority of nodes are correct.

### Failure Tolerance
Disconnections, collapses, restarts or other failures will not stop the service
or contaminate the committed records. The service remains available as long as a
majority of nodes are correct. Even if all nodes in the cluster fail, they can
re-cluster and recover to resume the service.

### RMI
RMI is used to save our lives with reliable RPCs between nodes.

## Classes and Roles
There are four roles; Follower, Candidate, Leader and Client.  

`FollowerState`, `CandidateState` and `LeaderState` extend `AbstractState`.  
The three classes implement the two abstract methods defined in `AbstractState` which are used for communication purposes as part of the Raft algorithm.

When a node changes its role, it can simply set its state to the target class.

## Dependencies
   - Java
   - Maven

## Usage
Compile,
```
mvn install
```
Modify the config file of each node for your Raft cluster with the format below,
```json
cat configs/nodeConfig1.json
{
    "nodeId":1,                     // unique id on cluster of this node
    "port":2222,
    "statePath":"./state1.json",    // persistent storage path
    "clusterInfo":[
        {
            "nodeId":2,             // unique id of one cluster node
            "address":"127.0.0.1",  // accessible ip of this node
            "port":3333             // accessible port of this node
        },
        {
            "nodeId":3,
            "address":"127.0.0.1",
            "port":4444
        }
        ... ...
    ]
}%
```
Run node N,
```shell
java -jar target/NodeStarter.jar configs/nodeConfigN.json
```
You will observe node X becoming a Leader with output like,
```
node #X: Connected to node #Y
... ...
node #X now is in FollowerState
node #X now is in CandidateState
node #X now is in LeaderState
```
Meanwhile, you will observe other nodes become Followers,
```
node #Y: Connected to node #X
... ...
node #Y: Connected to node #Z
node #Y now is in FollowerState
Node #Y received appendEntries RPC: prevLogIndex = 0, prevLogTerm = 0, term = 4
```
You can now use key-value database via Client (use `list` to find usage),
```
java -jar target/ClientStarter.jar configs/clusterInfo.json
list
Usage: send <command> [parameters]
Parameters:
 --node, -n: Node number
Commands:
set <key> <value> : set a key-value pair to the db
get <key> : get a value from the db
del <key> : delete a value from the db
keys : list all the keys in the db
Example: send set age 25 -n 1
```
