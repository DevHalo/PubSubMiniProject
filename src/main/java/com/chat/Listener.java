package com.chat;

import com.solacesystems.jcsmp.*;

import java.io.IOException;
import java.util.*;

public class Listener {

    // Listener application for chat program
    public static void main(String[] args) throws JCSMPException {
        Map<String, ArrayList<String>> messageHistory = new HashMap<>();

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

        System.out.println("Initializing...");
        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, args[0]);     // host:port
        properties.setProperty(JCSMPProperties.USERNAME, args[1].split("@")[0]); // client-username
        properties.setProperty(JCSMPProperties.PASSWORD, args[2]); // client-password
        properties.setProperty(JCSMPProperties.VPN_NAME, args[1].split("@")[1]); // message-vpn
        final JCSMPSession session = JCSMPFactory.onlyInstance().createSession(properties);

        Scanner in = new Scanner(System.in);
        System.out.println("Would you like to set a welcome message? A blank response will not have a welcome message");
        String input = in.nextLine();

        // Set up a welcome message for connecting clients
        final String welcomeMessage;
        if (input.length() != 0) {
            welcomeMessage = input;
        } else {
            welcomeMessage = "No welcome message.";
        }

        // New clients will send a request and await a reply from Listener from this topic
        final Topic lobby = JCSMPFactory.onlyInstance().createTopic("chat/lobby");

        // Used by clients to announce their subscription to a topic (chat room). The listener can reply back with
        // message history if such history exists.
        final Topic callback = JCSMPFactory.onlyInstance().createTopic("chat/heartbeat");

        // Used by the listener to track all message history
        final Topic rooms = JCSMPFactory.onlyInstance().createTopic("chat/rooms/*");

        // Producer for sending messages
        final XMLMessageProducer producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
            @Override
            public void responseReceived(String messageID) {
                System.out.println("Producer received response for msg: " + messageID);
            }

            @Override
            public void handleError(String messageID, JCSMPException e, long timestamp) {
                System.out.printf("Producer received error for msg: %s@%s - %s%n",
                        messageID, timestamp, e);
            }
        });

        // Consumer for retrieving messages
        final XMLMessageConsumer consumer = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage request) {
                if (request.getReplyTo() != null) {
                    switch (request.getDestination().getName()) {
                        case "chat/heartbeat":
                            // If the user joins, attempt to send them the message history if it exists.
                            String[] heartbeat = ((TextMessage)request).getText().split("-");
                            System.out.println(heartbeat[0] + " Joined " + heartbeat[2]);

                            // If the client is joining the topic
                            if (heartbeat[1].equals("join")) {
                                // If the room already existed, attempt to send them the message history
                                if (!messageHistory.containsKey("chat/rooms/" + heartbeat[2])) {
                                    System.out.println("No previous history. Sending ACK.");
                                    TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                                    msg.setText("ACK");

                                    try {
                                        producer.sendReply(request, msg);
                                    } catch (JCSMPException e) {
                                        e.printStackTrace();
                                    }

                                    messageHistory.put("chat/rooms/" + heartbeat[2], new ArrayList<>());
                                } else {
                                    System.out.println("Sending previous history to " + heartbeat[0]);
                                    // Prepare stream of strings to be sent
                                    ArrayList<String> msgList = messageHistory.get("chat/rooms/" + heartbeat[2]);

                                    StreamMessage textMsg = JCSMPFactory.onlyInstance().createMessage(StreamMessage.class);
                                    SDTStream stream = JCSMPFactory.onlyInstance().createStream();

                                    // If the message history contains more than 20 lines, only send the first 20 lines
                                    int count = msgList.size() > 20 ? msgList.size() - 20 : 0;

                                    if (msgList.size() > 20) {
                                        stream.writeString("-- Message history exceeded 20 lines. History pruned. --");
                                    }

                                    for (int i = count; i < msgList.size(); i++) {
                                        stream.writeString(msgList.get(i));
                                    }

                                    textMsg.setStream(stream);

                                    // Attempt to reply to the client
                                    try {
                                        producer.sendReply(request, textMsg);
                                    } catch (JCSMPException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            break;
                        case "chat/lobby":
                            System.out.println("A client joined the exchange. Sending welcome message...");
                            TextMessage reply = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
                            reply.setText(welcomeMessage);

                            try {
                                producer.sendReply(request, reply);
                            } catch (JCSMPException e) {
                                System.out.println("Error replying to client.");
                                e.printStackTrace();
                            }
                            break;
                    }
                } else {
                    // If the message received is not for welcome message
                    if (request instanceof TextMessage) {
                        if (request.getDestination().getName().equals("chat/heartbeat")) {
                            String[] heartbeat = ((TextMessage)request).getText().split("-");
                            if (heartbeat.length == 3) {
                                System.out.println(heartbeat[0] + " Left " + heartbeat[2]);
                            }
                        } else {
                            // A regular message was received, store it in the array.
                            if (!messageHistory.containsKey(request.getDestination().getName())) {
                                messageHistory.put(request.getDestination().getName(), new ArrayList<>());
                            }
                            messageHistory.get(request.getDestination().getName()).add(((TextMessage) request).getText());
                        }
                    }
                }
            }

            @Override
            public void onException(JCSMPException e) {
                System.out.printf("Consumer received exception: %s%n", e);
            }
        });

        // Subscribe to the lobby, heartbeat, and all rooms on the broker
        session.addSubscription(lobby);
        session.addSubscription(callback);
        session.addSubscription(rooms);
        consumer.start();

        System.out.print("\033[H\033[2J");
        System.out.flush();

        // Consume-only session is now hooked up and running!
        System.out.println("Listener is running. Press enter to exit");
        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Exiting...");
        consumer.close();
        producer.close();
        session.closeSession();
    }
}
