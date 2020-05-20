import java.rmi.Remote;
import java.rmi.RemoteException;

/* Remote interface for client interaction */
public interface IClientInterface extends Remote {
	/**
	 * Send command to one of the sever in the cluster
	 * @param command Command string e.g. "set id 1"
	 * @return Returned message. TODO: can be change to an object for more information
	 * @throws RemoteException Communication-related exceptions
	 */
	String sendCommand(String command, int timeout) throws RemoteException;
}
