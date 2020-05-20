# frp usage

Download release version ([MacOS](https://github.com/fatedier/frp/releases/download/v0.33.0/frp_0.33.0_darwin_amd64.tar.gz), [Linux](https://github.com/fatedier/frp/releases/download/v0.33.0/frp_0.33.0_linux_amd64.tar.gz))
<br >
Config, this example will forward the remote port `10001` to your local port `10001`,
```
cat frpc.ini
[common]
server_addr = 45.63.29.68
server_port = 10000
token = gyjJVw13OpJHMPT0prr

[10001]
type = tcp
local_ip = 127.0.0.1
local_port = 10001
remote_port = 10001
```
Run it,

```
frpc -c frpc.ini
2020/05/20 23:18:14 [I] [service.go:249] [c10fc86ad8e50d97] login to server success, get run id [c10fc86ad8e50d97], server udp port [0]
2020/05/20 23:18:14 [I] [proxy_manager.go:144] [c10fc86ad8e50d97] proxy added: [10001]
2020/05/20 23:18:16 [I] [control.go:164] [c10fc86ad8e50d97] [10001] start proxy success
``` 
Now you should be able to use `45.63.29.68:10001` access your local `localhost:10001`.