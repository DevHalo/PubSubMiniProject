package com.chat;

import com.solacesystems.jcsmp.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Client {

    // Stores our message history
    private static ArrayList<String> messageBuffer = new ArrayList<>();

    // Stores the thread-safe instance of our message history list
    private static List<String> safeMessageBuffer = Collections.synchronizedList(messageBuffer);

    // Client console application for chat program
    public static void main(String[] args) throws JCSMPException {
        // Check command line arguments
        if (args.length != 3 || args[1].split("@").length != 2) {
            System.out.println("Usage: TopicSubscriber <host:port> <client-username@message-vpn> <client-password>");
            System.out.println();
            System.exit(-1);
        }
        if (args[1].split("@")[0].isEmpty()) {
            System.out.println("No client-username entered");
            System.out.println();
            System.exit(-1);
        }
        if (args[1].split("@")[1].isEmpty()) {
            System.out.println("No message-vpn entered");
            System.out.println();
            System.exit(-1);
        }

        // Set broker credentials and attempt a connection
        System.out.println("Initializing...");
        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, args[0]);     // host:port
        properties.setProperty(JCSMPProperties.USERNAME, args[1].split("@")[0]); // client-username
        properties.setProperty(JCSMPProperties.PASSWORD, args[2]); // client-password
        properties.setProperty(JCSMPProperties.VPN_NAME,  args[1].split("@")[1]); // message-vpn
        final Topic lobbyTopic = JCSMPFactory.onlyInstance().createTopic("chat/lobby");
        final Topic requestTopic = JCSMPFactory.onlyInstance().createTopic("chat/heartbeat");
        final JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        // Producer
        XMLMessageProducer producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
            @Override
            public void handleError(String s, JCSMPException e, long l) {
                System.out.println("Producer received error for msg: %s@%s - %s%n");
            }

            @Override
            public void responseReceived(String s) {
                System.out.println("Producer received response for msg: " + s);
            }
        });

        // Initialize consumer for receiving messages
        XMLMessageConsumer consumer = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage msg) {
                if (msg instanceof TextMessage) {
                    // Regular chat
                    printChat();
                    messageBuffer.add(((TextMessage) msg).getText());
                    System.out.println(((TextMessage) msg).getText());
                }
            }

            @Override
            public void onException(JCSMPException e) {
                System.out.printf("Consumer received exception: %s%n", e);
            }
        });
        consumer.start();

        cls();
        try {
            System.out.println("Awaiting response from Listener...");

            // Initialize requester for retrieving a welcome message from a Listener connected to the broker
            Requestor requestor = session.createRequestor();
            TextMessage greet = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
            greet.setText("greet");

            // Reply timeout for 2000ms
            BytesXMLMessage reply = requestor.request(greet, 2000, lobbyTopic);

            if (reply instanceof TextMessage) {
                    System.out.printf("Listener callback greeting:\n%s\n", ((TextMessage) reply).getText());
            } else {
                System.out.println("Listener returned unexpected message type.");
            }
        } catch (JCSMPRequestTimeoutException e) {
            System.out.println("Reply timeout, Listener did not respond in time or does not exist.");
        }

        int state = 0; // 0 - menu, 1 - chat

        System.out.println("Press enter to continue...");
        Scanner in = new Scanner(System.in);
        in.nextLine();

        Topic room = null;
        String username = "noname";
        while(state != -1) {

            // Used to flush the list that stores the message history in a thread-safe manner
            Iterator<String> iter = safeMessageBuffer.iterator();
            while (iter.hasNext()) {
                iter.next();
                iter.remove();
            }

            // State 0 is for the main menu
            while (state == 0) {
                cls();
                System.out.println("Chat Options:\nUsername: " + username + "\n1. Join a room\n2. Set your username\n3. Quit");
                String choice = in.nextLine();

                switch (Integer.valueOf(choice)) {
                    case 1:
                        System.out.print("If the room does not exist, it will be created. If the room already exists," +
                                " the application will attempt to retrieve the message history of this room.\nEnter room name: ");

                        String roomname;
                        while((roomname = in.nextLine()).contains("/")) {
                            System.out.print("Do not start the room with \"/\".\nEnter room name: ");
                        }

                        room = JCSMPFactory.onlyInstance().createTopic("chat/rooms/" + roomname);
                        TextMessage joinMsg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);

                        // Send a request to the listener to see if the room was already made. If it exists, the
                        // listener will send a stream of strings (at most 20) of the last chat history
                        try {
                            joinMsg.setText(username + "-join-" + roomname);
                            BytesXMLMessage reply = session.createRequestor().request(joinMsg, 2000, requestTopic);

                            // StreamMessage means there was message history, otherwise it is chat messages
                            if (reply instanceof StreamMessage) {
                                SDTStream stream = ((StreamMessage) reply).getStream();

                                // Consume the stream until it is complete
                                while (stream.hasRemaining()) {
                                    try {
                                        messageBuffer.add(stream.readString());
                                    } catch (SDTException e) {
                                        e.printStackTrace();
                                    }
                                }
                            } else if (reply instanceof TextMessage) {
                                // If the room does not exist, we will get the literal string "ACK" from the listener.
                                // Any other response would mean the message was damaged on transport
                                if (!((TextMessage) reply).getText().equals("ACK")) {
                                    System.out.println("Malformed response from the listener.");
                                }
                            }
                        } catch (JCSMPRequestTimeoutException ignored) {
                            // No need to halt the program, the listener is just missing.
                            System.out.println("No response received from listener.");
                        }

                        // Subscribe to the room we joined and announce our presence
                        session.addSubscription(room);
                        joinMsg.reset();
                        joinMsg.setText(username + " has joined the room.");
                        producer.send(joinMsg, room);
                        state = 1;
                        break;
                    case 2:
                        // Change display name
                        System.out.print("Enter your new name: ");
                        username = in.nextLine();
                        break;
                    case 3:
                        // Quit
                        state = -1;
                        break;
                }
            }

            // State 1 is for the chat
            while(state == 1) {
                // Get user input
                String message = in.nextLine();

                if (message.startsWith("/help")) {
                    System.out.println("Help:\n/leave - Leave the chat\n/room - The name of the room");
                } else if (message.startsWith("/leave")) {
                    System.out.println("Disconnecting...");

                    // Set up leave message for the listener
                    TextMessage leave = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                    leave.setText(username + "-leave-" + room);
                    producer.send(leave, requestTopic);

                    // Unsubscribe from the room and report our departure
                    session.removeSubscription(room);
                    leave.reset();
                    leave.setText(username + " has left the room.");
                    producer.send(leave, room);
                    state = 0;
                } else if (message.startsWith("/room")) {
                    // Returns the name of the room to the user. This message is not sent to the broker.
                    Matcher m = (Pattern.compile("([^/]+$)")).matcher(room.getName());
                    if (m.find()) {
                        System.out.println("Room Name: " + m.group(1));
                    }
                } else {
                    // If the client input is not a command, it is a message. The format for the message is
                    // Username: <message>
                    TextMessage reply = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                    reply.setText(username + ": " + message);
                    producer.send(reply, room);
                }
            }
        }

        // Close services and exit the program
        System.out.println("Exiting...");
        consumer.close();
        producer.close();
        session.closeSession();
    }

    // Thread-safe function for printing the message history to the console
    private static synchronized void printChat() {
        cls();
        for (String aHistory : messageBuffer) {
            System.out.println(aHistory);
        }
    }

    // Clears the screen
    private static void cls() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }
}
