name := "amqp-client"

organization := "com.kinja"
 
version := "2.2.1" + (if (RELEASE_BUILD) "" else "-SNAPSHOT")
 
crossScalaVersions := Seq("2.13.0", "2.12.8", "2.11.12")

scalaVersion := "2.12.8"
 
val akkaVersion = "2.5.23"

scalacOptions ++= Seq(
	"-feature",
	"-deprecation",
	"-language:postfixOps"
)

libraryDependencies ++= Seq(
	"com.typesafe.akka"    %% "akka-actor"          % akkaVersion % Provided,
	"com.rabbitmq"         % "amqp-client"          % "5.6.0",
	"com.typesafe.akka"    %% "akka-testkit"        % akkaVersion  % Test,
	"org.scalatest"        %% "scalatest"           % "3.0.8" % Test,
	"junit"                % "junit"                % "4.12" % Test,
	"com.typesafe.akka"    %% "akka-slf4j"          % akkaVersion % Provided,
	"ch.qos.logback"       %  "logback-classic"     % "1.0.0" % Provided
)

testOptions in Test := Seq.empty
