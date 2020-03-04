# Languages

Cloudstate user functions can be implemented in any language that supports gRPC. That said, the Cloudstate gRPC protocol is typically too low level for user functions to effectively implement their business logic in. Hence, Cloudstate provides support libraries for multiple languages to allow developers to implement entities using idiomatic APIs.

@@toc { depth=1 }

@@@ index

* [JavaScript](javascript/index.md)
* [Java](java/index.md)
* [Go](go/index.md)
* [Kotlin](kotlin/index.md)

@@@

## 3rd party libraries
We are aware of external libraries implementing the **client Cloudstate protocol** in various other languages.

❗ **Though they are mentioned here, these libraries are often not directly maintained by the Cloudstate dev team and are therefore not supported**.
 
Here is a list of other, **non-official** libraries in various other languages:

| Library Name                                                                                          | Language  | Event-sourcing support?   | CRDT support?         | Stateless support?    |
|-------------------------------------------------------------------------------------------------------|-----------|---------------------------|-----------------------|-----------------------|
| [scala-support](https://github.com/cloudstateio/cloudstate/tree/master/scala-support/src/main)        | Scala     | **❗️**                    | **❗️**               | **❌**                 |
| [kotlin-support](https://github.com/cloudstateio/kotlin-support)                                      | Kotlin    | **❗️**                    | **❗️**               | **❌**                 |
| [cloudstate-csharp](https://github.com/nagytech/cloudstate-csharp)                                    | C#        | **✅**                    | **✅**               | **❌**                 |
| [python-support](https://github.com/marcellanz/cloudstate_python-support/tree/feature/python-support) | Python    | **❓**                    | **❓**               | **❌**                 |
| [cloudstate-rust](https://github.com/sleipnir/cloudstate-rust)                                        | Rust      | **❓**                    | **❓**               | **❌**                 |

### Legend:
- **✅** Supported
- **❌** Not supported
- **❗️** Partial support / Unstable (see details on the website)
- **❓** Support status unknown

## Cloudstate CLI
👆Please be aware that the **Cloudstate CLI** project allows you to easily kick-start a new client project. 
You can currently find it at https://github.com/sleipnir/cloudstate-cli
