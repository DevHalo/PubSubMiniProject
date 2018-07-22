# Simple Chat Room using the Solace Messaging API for Java (JCSMP)

A simple chat application written using Solace's API for Java. Contains the following components:
1. The "user", whom can subscribe to any chat room (topic). Can send and receive messages.
2. The "listener" or backend. A client subscribed to all topics currently available on the broker. Receives and stores all messages
sent through the broker for future clients. Upon subscribing to a topic the "listener" will push the message history to the new client.

Built using the sample project provided by [Solace](https://github.com/SolaceSamples/solace-samples-java) using their Java API.

## Requirements

You will need either their cloud-based service or Solace VMR to operate as the broker for this application.

You will also need the Solace Java API which can be found [here](http://dev.solace.com/downloads/).

## Build Instructions

Similar to how Solace's project is built using Intellij IDEA:

``./gradlew idea`` - To generate the meta files for idea if you have not done that yet.

``./gradlew assemble`` - To build.

To run the listener (Not required, but you will be unable to see message history as a client)

``./build/staged/bin/listener HOST:PORT USERNAME@VPN PASSWORD``

To run the client

``./build/staged/bin/client HOST:PORT USERNAME@VPN PASSWORD``

## Client Chat Commands

Not much. You can type the following in chat:

``/leave`` - Self-explanatory

``/room`` - Tells you the name of the room in case you forgot

## Screenshots

Since setting up could be a massive pain here's a demo:
![Demo](img/demo.gif?raw=true)