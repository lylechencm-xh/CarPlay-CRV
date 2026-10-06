# CR-V Android 4.2.2 / API17 shrink-only rules.
# Remove unused dependency code but keep source-visible names and execution structure stable.
-dontobfuscate
-dontoptimize

-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# LocalMfiAuthenticationClient instantiates BouncyCastleProvider and resolves these algorithms
# through JCA names. R8 cannot infer provider implementation classes from those strings.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EC$Mappings { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.X509$Mappings { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.x509.** { *; }

# Keep enum / Kotlin metadata behavior used by diagnostics and protocol parsing.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
