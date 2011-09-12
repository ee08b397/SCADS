SCADS (Scalable Consistency Adjustable Data Storage) is a research prototype distributed storage system used in the [RAD Lab](http://radlab.cs.berkeley.edu/) and the [AMP Lab](https://amplab.cs.berkeley.edu/) at [UC Berkeley](http://berkeley.edu/). The goals of the system were first described in [our vision paper from CIDR2009](http://radlab.cs.berkeley.edu/publication/185)

SCADS Sub-projects
=================
SCADS is composed of the following sub-projects:

SCADS Core
----------
* [config](https://github.com/radlab/SCADS/wiki/SCADS-Config) - Configuration file parsing.
* [avro-plugin](https://github.com/radlab/SCADS/wiki/Avro-Plugin) - A scala compiler plugin allowing case classes to be efficiently serialized using [Avro](http://avro.apache.org/) encoding.
* deploylib - A parallel ssh library for deploying jvm based experiments on remote servers and EC2.
* communication - [Netty](http://www.jboss.org/netty) based message passing of Avro encoded messages.  Scala library for using [Apache ZooKeeper](http://zookeeper.apache.org/).
* scala-engine - K/V storage optionally using [BDB](http://www.oracle.com/technetwork/database/berkeleydb/overview/index.html) for persistance.
* piql - The performance insightful query lanaguage and scale-independent relational optimizer.
* perf - A library of helpers for writing scads performance tests using deploylib.

Other Experiments
-----------------
* scadr - PIQL queries for a clone of [Twitter](http://www.twitter.com).
* matheron
* tpcw - PIQL queries for the [TPC-W](http://www.tpc.org/tpcw/) Benchmark.
* axer - Alternate Avro encoding optimized for random field access.
* modeling - experiments to characterize the performance of PIQL queries.

Deprecated Projects
-------------------
The following sub-projects are no longer actively maintained:

* [demo](https://github.com/radlab/SCADS/wiki/Demo) - The [RAD Lab final demo](http://radlab.cs.berkeley.edu/media-news/345) used SCADS along with other projects from the RAD Lab to scale web applications written by novice developers over a weekend to hundreds of servers on Amazon EC2.
* director - The director ensures SLO compliance for storage operations by using machine learning models to dynamically re-provision a scads storage cluster based on current and projected workload.  More details can be found in the paper from [FAST2011](http://www.eecs.berkeley.edu/~franklin/Papers/fast11director.pdf).

Third Party Components
----------------------
* optional - The optional command line parsing library from [paulp](https://github.com/paulp/optional) with added support for default arguments.

Building
========
SCADS is built using [SBT](https://github.com/harrah/xsbt).  The SBT launcher is included in the distribution (bin/sbt) and is responsible for downloading all other required jars (scala library/compiler and dependencies).

SBT commands can be invoked from the command line.  For example to clean and build jar files for the entire scads project you would run the following command:

    ~/scads$ sbt clean package

You can also execute commands on specific sub projects by specifying `<subproject>/<command>`.  For example:

    ~/scads$ sbt piql/compile

Additionally if you are going to be running several commands you can use sbt from an interactive console, which amortizes the cost of starting the JVM and JITing sbt and the scala compiler.  For example:

    scads> sbt
    [info] Loading project definition from /Users/marmbrus/Workspace/radlab/scads/project
    [info] Set current project to scads (in build file:/Users/marmbrus/Workspace/radlab/scads/)
    scads:sbt09> project modeling
    [info] Set current project to modeling (in build file:/Users/marmbrus/Workspace/radlab/scads/)
    modeling:sbt09> compile
    [success] Total time: 7 s, completed Sep 11, 2011 5:23:28 PM

Useful Command Reference
------------------------
* `clean` - delete generated files
* `compile` - build the current project and all its dependencies
* `doc` - compile scaladoc
* `package` - create the jar or war file for the current project
* `project <subproject name>` - switch to the specified subproject, subsequent commands will be run only on this project and its dependencies
* `projects` - list all of the available subprojects
* `publish` - publish jars to the radlab nexus repository
* `publish-local` - publish jars to your local ivy cache.
* `reload` - recompiles the project definition and restarts sbt
* `run` - run the mainclass for the current subproject, if there are multiple choices you will be prompted for which one you want to run
* `test` - run the testcases for the current subproject and all its dependencies
* `test-only [test case]` - run the specified testcase or only the testcases who's source has changed since tests were last run
* `update` - download all managed dependencies jars, note in contrast to maven this must be run explicitly.  This only needs to be run once unless dependencies have been added to the project.