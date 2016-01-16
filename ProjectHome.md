## Introduction ##
The Constrained Application Protocol (CoAP) is a specialized web transfer protocol for use with constrained networks and nodes for machine-to-machine applications. Currently, CoAP is an IETF draft (https://datatracker.ietf.org/doc/draft-ietf-core-coap/). As a result, jCoAP is an early-stage project that implements CoAP coming from the University of Rostock. It is compatible to Java SE and Android.

Feedback and contributions to this project are welcome either by contacting the project owners or through the WS4D initiative (http://www.ws4d.org).

**Implemented Features:**
  * Reliable and Unreliable Messaging
  * Piggy-backed and Separate Response
  * Simple to Use API
  * Proxy implementation (CoAP-CoAP, CoAP-HTTP, HTTP-CoAP)

**Quickstart:**
Checkout the source code. The source code contains the folder _ws4d-jcoap_ and _ws4d-jcoap-applications_. Both folder contain an Eclipse project file. Use the Eclipse import function to import both projects. In ws4d-jcoap-applications a basic server and a basic client example can be found.

## Announcing the CoAP/HTTP-Proxy for jCoAP ##

Today we like to announce the jCoAP CoAP/HTTP-Proxy, an open source CoAP-to-HTTP and HTTP-to-CoAP proxy implementation. Beside the purposes of a traditional HTTP proxy, the CoAP/HTTP-Proxy can perform protocol translations between CoAP and HTTP. This allows pure HTTP clients to access CoAP end-points. As a result HTTP clients won’t need to implement the CoAP protocol, while the network can still benefit from the efficiency (e.g., low protocol overhead) of CoAP.

The proxy can be found at git/ws4d-jcoap-applications/org.ws4d.coap.proxy/Proxy.java. As a command line parameter an integer value is expected. This parameter defines the default caching time (“0” to disable caching). A more detailed description of the CoAP/HTTP-Proxy will follow soon.

**This work has been sponsored by Siemens Corporate Technology.**