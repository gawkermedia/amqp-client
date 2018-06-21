resolvers :=
	Seq("Kinja Public Group" at sys.env.get("KINJA_PUBLIC_REPO").getOrElse("https://kinjajfrog.jfrog.io/kinjajfrog/sbt-virtual"),
	"sonatype-releases" at "https://oss.sonatype.org/content/repositories/releases/")

credentials += Credentials(Path.userHome / ".ivy2" / ".kinja-artifactory.credentials")

// Kinja build plugin
addSbtPlugin("com.kinja.sbtplugins" %% "kinja-build-plugin" % "3.2.1")
