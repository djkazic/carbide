# gRPC
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**

# Protobuf
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# LND proto generated classes
-keep class lnrpc.** { *; }
-keep class routerrpc.** { *; }
-keep class invoicesrpc.** { *; }

# lnd-mobile (gomobile)
-keep class lndmobile.** { *; }
-dontwarn lndmobile.**
-keep class go.** { *; }
-dontwarn go.**
