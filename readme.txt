# Raft Key-value Database
<strong>NOTE: Use a Markdown viewer to have better experience</strong>
## Description
This is a key-value database using Raft distributed algorithm in Java.
The paper of Raft can be found [here](https://raft.github.io/raft.pdf).
In this implementation, Json files are used as persist storage.
Records that are successfully committed by the leader can be applied to the state machine, which is 
the actual key-value database.
### Consistency
The stored records can be read from any node of the working Raft cluster at any time.
### Failure Tolerance
Disconnecting, collapse, restarting and any other failures will not stop the service 
or contaminate the committed records, even all the nodes on cluster exit and re-cluster.
### RMI
RMI is used to save our lives with reliable RPCs between nodes.
## Classes and Roles
There are four roles which are Follower, Candidate, Leader and Client. <br >
Class FollowerState, CandidateState and LeaderState extend the class named AbstractState. 
The three classes implement methods defined in AbstractStates which used to achieve the key-word dataset. <br >
When the role change happens on a node, the node can simply set its state to the target class.
## Dependencies
   - Java
   - Maven
   
## Usage
Compile,
```
mvn install
```
Configure the config file of each node for your Raft cluster  with the format below,
```
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
```$xslt
java -jar target/NodeStarter.jar configs/nodeConfigN.json
```
You will observe node X becomes a Leader with output like,
```
node #X: Connected to node #Y
... ...
node #X now is in FollowerState
node #X now is in CandidateState
node #X now is in LeaderState
```
During that, you will observe other nodes become Followers,
```
node #Y: Connected to node #X
... ...
node #Y: Connected to node #Z
node #Y now is in FollowerState
Node #Y received appendEntries RPC: prevLogIndex = 0, prevLogTerm = 0, term = 4
```
You can now use key-value dataset via Client (use `list` to find usage),
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