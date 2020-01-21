description := "Proto files and generated gRPC entities and servers for DEX-Node interaction"

libraryDependencies ++= Dependencies.Module.wavesGrpc

inConfig(Compile)(
  Seq(
    PB.deleteTargetDirectory := false,
    PB.protoSources in Compile += PB.externalIncludePath.value,
    PB.targets += scalapb.gen(flatPackage = true) -> sourceManaged.value,
    // Google's descriptor.proto contains a deprecated field:
    //  optional bool java_generate_equals_and_hash = 20 [deprecated=true];
    // When scalac compiles a generated class, it warns about a deprecated field.
    // We can't disable generation of gRPC entities, because they are used and generated by "com.wavesplatform" % "protobuf-schemas"
    // Also see https://github.com/scala/bug/issues/7934
    scalacOptions := scalacOptions.value.filterNot(x => x == "-Xfatal-warnings")
  )
)