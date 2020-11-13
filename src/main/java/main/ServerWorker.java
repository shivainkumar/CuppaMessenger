package main;

import com.google.gson.Gson;

import java.io.*;
import java.net.Socket;
import java.util.Timer;
import java.util.TimerTask;

public class ServerWorker extends Thread {

	private final String workerId;
	private final Server server;
	private final Socket client;
	private User user;
	private Timer heartbeat;
	private final InputStream in;
	private final OutputStream out;
	private boolean isAuth;
	
	protected ServerWorker(String workerId, Server server, Socket client) throws IOException {
		this.workerId = workerId;
		this.server = server;
		this.client = client;
		user = new User();
		in = client.getInputStream();
		out = client.getOutputStream();
		isAuth = false;
	}

	protected String getWorkerId(){
		return workerId;
	}

	public void run() {
		try {
			countdownHeartbeatTimer();
			authenticateUser();
			heartbeat.cancel();
			System.out.println("Stopping client thread.");
		}
		catch(Exception ex) {
			ex.printStackTrace();
		}
	}

	private void countdownHeartbeatTimer(){
		heartbeat = new Timer();
		heartbeat.schedule(new TimerTask(){

			@Override
			public void run() {
				try {
					removeThisUser();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}, 10000);
	}

	private void resetHeartbeatTimer(){
		System.out.println("Heartbeat reset");
		heartbeat.cancel();
		countdownHeartbeatTimer();
	}

	protected void removeThisUser() throws IOException {
		if(isAuth) server.removeUser(user);
		server.removeClient(workerId);
	}

	protected String getUsername(){
		return user.getUsername();
	}

	protected void send(Message msg) throws IOException {
		DataOutputStream dout = new DataOutputStream(out);
		Gson gson = new Gson();

		dout.writeUTF(gson.toJson(msg) + "\n");
		dout.flush();
	}

	private void authenticateUser() throws IOException{
		DataInputStream input = new DataInputStream(in);
		DataOutputStream dout = new DataOutputStream(out);
		Gson gson = new Gson();
		Message msg;

		//send client welcome message
		send(new Message("server", user.getUsername(), "server_to_client", "welcome_message", "success"));


		String line;
		while((line = input.readUTF()) != null) {
			msg = (Message) gson.fromJson(line, Message.class);


			if(msg.type.equals("MSG-ARRAY") ){
				String[] credentials = gson.fromJson(msg.message, String[].class);
				User this_user = server.authenticateCredentials(credentials[0], credentials[1]);

				if(this_user != null){
					isAuth = true;
					user = this_user;

					send(new Message("server", user.getUsername(), "MSG-RESULT", "login_credentials", gson.toJson(this_user)));
					server.addUser(this_user);
					System.out.println("success login");
					break;
				}
				else{
					send(new Message("server", user.getUsername(), "MSG-RESULT", "login_credentials", "fail"));
				}
			}
			resetHeartbeatTimer();
		}

		
		if(isAuth)
			listenForClientRequests();
	}
	
	private void listenForClientRequests() throws IOException {
		System.out.println(user.getUsername() + " logged in to the server");
		DataInputStream input = new DataInputStream(in);
		String line;
		Gson gson = new Gson();
		Message msg;

		try {
			while ((line = input.readUTF()) != null) {

				msg = (Message) gson.fromJson(line, Message.class);
				resetHeartbeatTimer();

				//if heartbeat skip
				if (msg.subject.equalsIgnoreCase("hearbeat")) {
					continue;
				}

				//send message to another user
				if (msg.subject.equals("user_to_user") && !msg.message.equalsIgnoreCase("online_users")) {
					server.sendToClient(msg);
				}
				//send message to group
				else if (msg.type.equals("user_to_group")) {
					server.sendToGroup(msg);
				}
				//send user list of online users
				else if (msg.message.equalsIgnoreCase("online_users")) {

					Message info = new Message("server", msg.from, "MSG-RESULT", "online_users", server.getOnlineUsers());
					send(info);
				}
				//send user list of all registered users
				else if (msg.message.equalsIgnoreCase("all_users")) {

					Message info = new Message("server", msg.from, "MSG-RESULT", "all_users", server.getAllUsers());
					send(info);
				}
				//change users status
				else if (msg.subject.equalsIgnoreCase("set_status")) {
					user.setStatus(msg.message);
					server.broadcastNotify(getUsername(), "user_status_change", msg.message);
				}
				//disconnect from the server
				else if (msg.message.equalsIgnoreCase("quit")) {
					out.write("disconnecting... \n".getBytes());
					break;
				}

			}
		}
		catch(IOException e){
			removeThisUser();
			client.close();
		}

		removeThisUser();
		client.close();
	}
}
