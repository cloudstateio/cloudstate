# The CloudState Build

The CloudState Build uses `sbt` as its build tool.

## Installation instructions

* Install the `git` source code manager from [here](https://git-scm.com/).
* Clone the CloudState repository using `git`: `git clone git@github.com:cloudstateio/cloudstate.git`
* Install `sbt`, follow [these instructions](https://www.scala-sbt.org/download.html).

## Getting started

It is possible to run `sbt` either on a command-by-command basis by running `sbt <command> parameters`. It is also possible to run `sbt` in interactive mode by running `sbt`, then you can interact with the build interactively by typing in commands and having them executed by pressing RETURN.

The following is a list of sbt commands and use-cases:

| Command        | Use-case                                     |
| -------------: | -------------------------------------------- |
| projects       | Prints a list of all projects in the build |
| project <NAME> | Makes <NAME> the current project |
| clean          | Deletes all generated files and compilation results for the current project and the projects it depends on |
| compile        | Compiles all non-test sources for the current project and the projects it depends on |
| test:compile   | Compiles all sources for the current project and the projects it depends on |
| test | Executes all "regular" tests for the current project and the projects it depends on |
| it:test | Executes all integration tests for the current project and the projects it depends on |
| exit | Exits the interactive mode of `sbt` |

For more documentation about `sbt`, see [this page](https://www.scala-sbt.org/1.x/docs/index.html).