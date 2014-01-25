/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.tests;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.net.ServerSocket;
import com.badlogic.gdx.net.Socket;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.StringBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Arrays;

/** For testing of LwjglHeadlessApplication (headless server)
 * @author Jon Renner */
public class HeadlessTest extends GdxTest {

	@Override
	public void create () {
		System.out.println("Starting headless test...");
		new Thread(new HeadlessServer("LibGDX Headless Test")).start();
		makeWorld();
	}

	private World world;
	private static final Array<Body> bodies = new Array<Body>();

	void makeWorld() {
		world = new World(new Vector2(0, 0), true);
		BodyDef bdef = new BodyDef();
		bdef.type = BodyDef.BodyType.DynamicBody;
		for (int i = 0; i < 5; i++) {
			bodies.add(world.createBody(bdef));
		}
	}

	private static final float TIME_STEP = 1/60f;

	@Override
	public void render() {
		synchronized (bodies) {
			world.step(TIME_STEP, 8, 3);
			Body randomBody = bodies.random();
			Vector2 pos = randomBody.getPosition();
			randomBody.applyForceToCenter(-(pos.x * 2f), -(pos.y * 2f), true);
		}
		System.out.print(".");
	}

	private static final int DEFAULT_PORT = 11209;
	private static final String DEFAULT_HOST = "localhost";

	private static enum Request {
		Identify((byte) 0x01), // get server name
		NumClients((byte) 0x02), // get num of connected clients
		Shutdown((byte) 0x03), // shutdown server
		BodyData((byte) 0x04), // get box2d body info
		;

		private static byte prefix = 0x7D;
		private byte code;
		Request(byte code) {
			this.code = code;
		}

		public byte getCode() {
			return code;
		}

		public static Request getRequest(byte code) {
			for (Request req : Request.values()) {
				if (req.getCode() == code) {
					return req;
				}
			}
			return null;
		}
	}

	class clientHandler implements Runnable {
		private HeadlessServer server;
		private Socket clientSock;

		public clientHandler(Socket socket, HeadlessServer server) {
			this.server = server;
			this.clientSock = socket;
		}

		@Override
		public void run() {
			while (clientSock.isConnected()) {
				server.incrementActiveClients();
				InputStream inStream = clientSock.getInputStream();
				PrintWriter writer = new PrintWriter(clientSock.getOutputStream());
				byte[] buf = new byte[256];
				try {
					while (inStream.read(buf) != -1) {
						Request request = checkBufferForRequest(buf);
						if (request != null) {
							handleRequest(request, writer);
						}
					}
					clientSock.dispose();
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					server.decrementActiveClients();
				}
			}
		}

		private Request checkBufferForRequest(byte[] buf) {
			int adjustedLength = buf.length - 1; // code is one byte for prefix and one byte for request
			for (int i = 0; i < adjustedLength; i++) {
				byte first = buf[i];
				if (first == Request.prefix) {
					// got prefix, next byte should be request code
					byte second = buf[i + 1];
					Request req = Request.getRequest(second);
					if (req != null) {
						return req;
					}
				}
			}
			return null;
		}

		private void handleRequest(Request request, PrintWriter clientWriter) {
			switch (request) {
				case Identify:
					clientWriter.write("SERVER ID: " + server.identifier);
					clientWriter.flush();
					break;
				case NumClients:
					clientWriter.write("ACTIVE CLIENTS: " + server.getActiveClients());
					clientWriter.flush();
					break;
				case Shutdown:
					clientWriter.write("SHUTTING DOWN SERVER");
					clientWriter.flush();
					System.out.println("Shutdown ordered by client, exiting...");
					System.exit(0);
					break;
				case BodyData:
					StringBuilder sb = new StringBuilder();
					sb.append("Box2D bodies:\n");
					synchronized (bodies) {
						int i = 0;
						for (Body body : bodies) {
							sb.append("    Body #").append(i++).append("\n");
							sb.append("        Position: ").append(body.getPosition()).append("\n");
							sb.append("        Velocity: ").append(body.getPosition()).append("\n");
							sb.append("        Angle:    ").append(body.getAngle()).append("\n");
						}
					}
					clientWriter.write(sb.toString());
					clientWriter.flush();
					break;
				default:
					String msg = "unhandled request type: " + request.toString();
					clientWriter.write(msg);
					clientWriter.flush();
					System.out.println(msg);
			}
		}
	}

	class HeadlessServer implements Runnable {
		private String identifier;
		private int activeClients;

		public HeadlessServer(String identifier) {
			this.identifier = identifier;
			System.out.println("Created server: " + identifier);
		}

		@Override
		public void run() {
			// test a simple server
			ServerSocket server = Gdx.net.newServerSocket(Net.Protocol.TCP, DEFAULT_PORT, null);

			while (true) {
				Socket clientSock = server.accept(null);
				new Thread(new clientHandler(clientSock, this)).start();
			}
		}

		private synchronized void incrementActiveClients() {
			activeClients = activeClients + 1;
		}

		private synchronized void decrementActiveClients() {
			activeClients = activeClients - 1;
		}

		private synchronized int getActiveClients() {
			return activeClients;
		}
	}

	class HeadlessClient {
		private Socket serverSock;

		public HeadlessClient() {
			System.out.println("Creating new client...");
			serverSock = Gdx.net.newClientSocket(Net.Protocol.TCP, DEFAULT_HOST, DEFAULT_PORT, null);
		}

		public void sendRequest(Request req) {
			if (serverSock == null) {
				throw new GdxRuntimeException("server socket unavailable");
			}
			OutputStream out = serverSock.getOutputStream();
			byte[] msg = new byte[2];
			msg[0] = Request.prefix;
			msg[1] = req.getCode();
			try {
				out.write(msg);
				out.flush();
			} catch (IOException e) {
				System.out.println("problem sending to server");
				e.printStackTrace();
			}
			System.out.println("sent request to server: " + req);

			byte[] buf = new byte[2048];
			InputStream in = serverSock.getInputStream();
			// we don't handle incoming data larger than the buffer because we don't care, this is just a test
			try {
				int n = in.read(buf);
				if (n != -1) {
					String serverMsg = new String(Arrays.copyOfRange(buf, 0, n));
					System.out.println("Received from server: " + serverMsg);
				}
			} catch (IOException e) {
				System.out.println("problem reading from server");
				e.printStackTrace();
			}
		}

		public void close() {
			serverSock.dispose();
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}

