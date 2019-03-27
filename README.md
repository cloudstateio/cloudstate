# Stateful Serverless

## Scalable Compute needs Scalable State
Bringing stateful microservices, and the power of reactive technologies to the Cloud Native ecosystem breaks down the final impediment standing in the way of a Serverless platform for general-purpose application development, true elastic scalability, and global deployment in the Kubernetes ecosystem. The marriage of Knative and Akka Cluster on Kubernetes allows applications to not only scale efficiently, but to manage distributed state reliably at scale while maintaining its global or local level of data consistency, opening up for a whole range of new addressable use-cases.

## TL;DR
* The Serverless Developer Experience, from development to production, is revolutionary and will grow to dominate the future of Cloud Computing
  * FaaS is however— with its ephemeral, stateless, and short-lived functions—only the first step/implementation of the Serverless Developer Experience. 
  * FaaS is great for processing intensive, parallelizable workloads, moving data from A to B providing enrichment and transformation along the way. But it is quite limited and constrained in what use-cases it addresses well, which makes it very hard/inefficient to implement traditional application development and distributed systems protocols. 
* What’s needed is a next generation Serverless platform and programming model for  general-purpose application development (e.g. microservices, streaming pipelines, ML, etc.). 
  * One that lets us implement use cases such as: shopping carts, user sessions, transactions, ML models training, low-latency prediction serving, job scheduling, and more.  
  * What is missing is support for long-lived virtual stateful services, a way to manage distributed state in a scalable and available fashion, and options for choosing the right consistency model for the job. 
* This next generation Serverless can be built on Knative/Kubernetes, gRPC, and Akka (Cluster, Persistence, etc.).

See the [rationale](RATIONALE.md) document for a more context.

Read the current [documentation](documentation/README.md).
