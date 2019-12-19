# Languages

## Officially supported client libraries
Cloudstate user functions can be implemented in any language that supports gRPC. That said, the Cloudstate gRPC protocol is typically too low level for user functions to effectively implement their business logic in. Hence, Cloudstate provides support libraries for multiple languages to allow developers to implement entities using idiomatic APIs.

@@toc { depth=1 }

@@@ index

* [JavaScript](javascript/index.md)
* [Java](java/index.md)
* [Go](go/index.md)

@@@

## Unofficial client libraries
We are aware of external libraries implementing the **client Cloudstate protocol** in various other languages.

:warning: **Though they are mentioned here, these libraries are often not directly maintained by the Cloudstate dev team and are therefore not supported**.
 
Here is a list of other, **non-official** libraries in various other languages:

| Library Name | Language | Event-based support? | CRDT support? |
|--------------|----------|----------------------|---------------|
| [scala-support](https://github.com/cloudstateio/cloudstate/tree/master/scala-support/src/main) | Scala | :warning: | :warning: |
| [kotlin-support](https://github.com/cloudstateio/kotlin-support) | Kotlin | :warning: | :warning: |
| [cloudstate-csharp](https://github.com/nagytech/cloudstate-csharp)| C# | :heavy_check_mark: | :heavy_check_mark: | 
| [python-support](https://github.com/marcellanz/cloudstate_python-support/tree/feature/python-support)| Python | :question: | :question: |
| [cloudstate-rust](https://github.com/sleipnir/cloudstate-rust)| Rust | :question: | :question: |

### Legend:
- :heavy_check_mark: Supported
- :x: Not supported
- :warning: Partial support / Unstable (see details on the website)
- :question: Support status unknown

## Cloudstate CLI
:point_up_2: Please be aware that the **Cloudstate CLI** project allows you to easily kick-start a new client project. 
You can currently find it at https://github.com/sleipnir/cloudstate-cli
