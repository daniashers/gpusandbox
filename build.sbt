name := "gpu-sandbox"

version := "1.0"

scalaVersion := "2.12.1"

libraryDependencies ++= Seq(
  "org.jogamp.gluegen" % "gluegen-rt-main" % "2.3.1",
  "org.jogamp.jocl" % "jocl-main" % "2.3.1",
  "org.jogamp.jogl" % "jogl-all" % "2.3.1",

  "com.github.sarxos" % "webcam-capture" % "0.3.10"
)


