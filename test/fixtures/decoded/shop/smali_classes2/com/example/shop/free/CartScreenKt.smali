.class public final Lcom/example/shop/free/CartScreenKt;
.super Ljava/lang/Object;
.source "CartScreen.kt"


# direct methods
.method public static final EmptyCart(Landroidx/compose/runtime/Composer;I)V
    .locals 26

    const-string v0, "C(EmptyCart)42@1337L64:CartScreen.kt#x1y2z3"

    invoke-static {p0, v0}, Landroidx/compose/runtime/ComposerKt;->sourceInformation(Landroidx/compose/runtime/Composer;Ljava/lang/String;)V

    const-string v1, "Your cart is empty"

    const/4 v2, 0x0

    invoke-static/range {v1 .. v25}, Landroidx/compose/material3/TextKt;->Text--4IGK_g(Ljava/lang/String;Landroidx/compose/ui/Modifier;JJLandroidx/compose/ui/text/font/FontStyle;Landroidx/compose/ui/text/font/FontWeight;Landroidx/compose/ui/text/font/FontFamily;JLandroidx/compose/ui/text/style/TextDecoration;Landroidx/compose/ui/text/style/TextAlign;JIZIILkotlin/jvm/functions/Function1;Landroidx/compose/ui/text/TextStyle;Landroidx/compose/runtime/Composer;III)V

    return-void
.end method
