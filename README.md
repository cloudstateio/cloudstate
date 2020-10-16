# Cloudstate - Next Generation Serverless

_"We predict that serverless computing will grow to dominate the future of cloud computing."_

—Berkeley CS dept, ['Cloud computing simplified: a Berkeley view on serverless computing'](https://arxiv.org/abs/1902.03383)

Bringing _stateful_ services, fast data/streaming, and the power of reactive technologies to the Cloud Native ecosystem breaks down the final impediment standing in the way of a **Serverless platform for general-purpose application development** — with true elastic scalability, high resilience, and global deployment, in the Kubernetes ecosystem.

The Serverless movement today is very focused on the automation of the underlying infrastructure, but it has to some extent ignored the equally complicated requirements at the application layer, where the move towards Fast Data, streaming, and event-driven stateful architectures creates all sorts of new challenges for operating systems in production.

Stateless functions are a great tool that has its place in the cloud computing toolkit, but for Serverless to reach the grand vision that the industry is demanding of a Serverless world while allowing us to build modern data-centric real-time applications, we can't continue to ignore the hardest problem in distributed systems: managing state—your data.

The [Cloudstate](https://cloudstate.io) project takes on this challenge and paves the way for Serverless 2.0. It consists of two things:

1. **A standards effort** — defining a specification, protocol between the user functions and the backend, and a TCK.
2. **A reference implementation** — implementing the backend and a set of client API libraries in different languages.

Cloudstate's reference implementation is leveraging [Knative](https://cloud.google.com/knative/), [gRPC](https://grpc.io/), [Akka Cluster](https://doc.akka.io/docs/akka/current/index-cluster.html), and [GraalVM](https://www.graalvm.org/) running on [Kubernetes](https://kubernetes.io/), allowing applications to not only scale efficiently, but to manage distributed state reliably at scale while maintaining its global or local level of data consistency, opening up for a whole range of new addressable use-cases.

Join us in making this vision a reality!

For more information see [cloudstate.io](https://cloudstate.io) and the Cloudstate [documentation](https://cloudstate.io/docs/).

---
## Get involved

Are you interested in helping out making this vision a reality? We would love to have you!
All contributions are welcome: ideas, criticism, praise, code, bug fixes, docs, buzz, etc.

Our [Mailing List](https://groups.google.com/forum/#!forum/cloudstate) is a good place to start with open ended discussions about Cloudstate, or you can also join the discussion on our [Gitter Channel](https://gitter.im/Cloudstate-IO/community).


The [GitHub Issue Tracker](https://github.com/cloudstateio/cloudstate/issues) is a good place to raise issues, including bug reports and feature requests.

You can also [follow us on Twitter](https://twitter.com/CloudstateIO).
