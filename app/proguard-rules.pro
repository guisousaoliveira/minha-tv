# Código do app fica inteiro (só as bibliotecas são otimizadas)
-keep class dev.guisousa.minhatv.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
