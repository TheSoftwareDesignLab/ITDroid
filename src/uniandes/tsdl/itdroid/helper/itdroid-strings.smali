# Added to an app by ITDroid (helper/CodeLiteralExtractor).
#
# A hardcoded string of the code is left where it is and handed to t(), which returns the string
# resource ITDroid created for that text, in the language the app is running in:
#
#     const-string v1, "Welcome back"
#     invoke-static/range {v1 .. v1}, Litdroid/hc/Strings;->t(Ljava/lang/String;)Ljava/lang/String;
#     move-result-object v1
#
# The resource is found by name: "itdroid_code_" followed by the hexadecimal hashCode of the text.
# When there is no such resource, or the app is not far enough into its start to have one (no
# Application object yet), or anything fails, t() returns the text it was given: the app then
# behaves exactly as it did before.
#
# The equivalent Java:
#
#     public static String t(String text) {
#         if (text == null) return null;
#         try {
#             Context c = context;
#             if (c == null) { c = current(); if (c == null) return text; context = c; }
#             Integer id = IDS.get(text);
#             if (id == null) {
#                 String name = "itdroid_code_" + Integer.toHexString(text.hashCode());
#                 id = Integer.valueOf(c.getResources().getIdentifier(name, "string", c.getPackageName()));
#                 IDS.put(text, id);
#             }
#             return id.intValue() == 0 ? text : c.getResources().getString(id.intValue());
#         } catch (Throwable e) { return text; }
#     }

.class public final Litdroid/hc/Strings;
.super Ljava/lang/Object;
.source "Strings.java"


# static fields
.field private static final IDS:Ljava/util/concurrent/ConcurrentHashMap;

.field private static volatile context:Landroid/content/Context;


# direct methods
.method static constructor <clinit>()V
    .locals 1

    new-instance v0, Ljava/util/concurrent/ConcurrentHashMap;

    invoke-direct {v0}, Ljava/util/concurrent/ConcurrentHashMap;-><init>()V

    sput-object v0, Litdroid/hc/Strings;->IDS:Ljava/util/concurrent/ConcurrentHashMap;

    return-void
.end method

.method private constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

# The Application object of the running process, or null before it exists.
.method private static current()Landroid/content/Context;
    .locals 4

    const/4 v0, 0x0

    :try_start_0
    const-string v1, "android.app.ActivityThread"

    invoke-static {v1}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;

    move-result-object v1

    const-string v2, "currentApplication"

    const/4 v3, 0x0

    new-array v3, v3, [Ljava/lang/Class;

    invoke-virtual {v1, v2, v3}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;

    move-result-object v1

    const/4 v2, 0x0

    new-array v2, v2, [Ljava/lang/Object;

    invoke-virtual {v1, v0, v2}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v1

    check-cast v1, Landroid/content/Context;
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    return-object v1

    :catch_0
    move-exception v1

    return-object v0
.end method

.method public static t(Ljava/lang/String;)Ljava/lang/String;
    .locals 6

    if-nez p0, :cond_0

    return-object p0

    :cond_0
    :try_start_0
    sget-object v0, Litdroid/hc/Strings;->context:Landroid/content/Context;

    if-nez v0, :cond_2

    invoke-static {}, Litdroid/hc/Strings;->current()Landroid/content/Context;

    move-result-object v0

    if-nez v0, :cond_1

    return-object p0

    :cond_1
    sput-object v0, Litdroid/hc/Strings;->context:Landroid/content/Context;

    :cond_2
    sget-object v1, Litdroid/hc/Strings;->IDS:Ljava/util/concurrent/ConcurrentHashMap;

    invoke-virtual {v1, p0}, Ljava/util/concurrent/ConcurrentHashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v2

    check-cast v2, Ljava/lang/Integer;

    if-nez v2, :cond_3

    new-instance v2, Ljava/lang/StringBuilder;

    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V

    const-string v3, "itdroid_code_"

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Ljava/lang/String;->hashCode()I

    move-result v3

    invoke-static {v3}, Ljava/lang/Integer;->toHexString(I)Ljava/lang/String;

    move-result-object v3

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;

    move-result-object v3

    const-string v4, "string"

    invoke-virtual {v0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;

    move-result-object v5

    invoke-virtual {v3, v2, v4, v5}, Landroid/content/res/Resources;->getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I

    move-result v2

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    invoke-virtual {v1, p0, v2}, Ljava/util/concurrent/ConcurrentHashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;

    :cond_3
    invoke-virtual {v2}, Ljava/lang/Integer;->intValue()I

    move-result v1

    if-nez v1, :cond_4

    return-object p0

    :cond_4
    invoke-virtual {v0}, Landroid/content/Context;->getResources()Landroid/content/res/Resources;

    move-result-object v2

    invoke-virtual {v2, v1}, Landroid/content/res/Resources;->getString(I)Ljava/lang/String;

    move-result-object v1
    :try_end_0
    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0

    return-object v1

    :catch_0
    move-exception v0

    return-object p0
.end method
