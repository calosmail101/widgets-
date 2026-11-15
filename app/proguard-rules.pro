-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.palanca.launcher.**$$serializer { *; }
-keepclassmembers class dev.palanca.launcher.** { *** Companion; }
-keepclasseswithmembers class dev.palanca.launcher.** { kotlinx.serialization.KSerializer serializer(...); }

# Commons Compress y Apache FtpServer/MINA traen clases opcionales que Android no tiene
# (JMX, Spring, compresores que no se usan): se ignoran los avisos y se conservan las del servidor.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.mina.**
-dontwarn org.slf4j.**
-dontwarn org.springframework.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
