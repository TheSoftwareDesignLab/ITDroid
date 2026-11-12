.class public Lcom/example/shop/ui/MainActivity;
.super Landroidx/appcompat/app/AppCompatActivity;
.source "MainActivity.java"


# instance fields
.field private addButton:Landroid/widget/Button;


# virtual methods
.method protected onCreate(Landroid/os/Bundle;)V
    .locals 2

    invoke-super {p0, p1}, Landroidx/appcompat/app/AppCompatActivity;->onCreate(Landroid/os/Bundle;)V

    const-string v0, "Shop"

    invoke-virtual {p0, v0}, Lcom/example/shop/ui/MainActivity;->setTitle(Ljava/lang/CharSequence;)V

    iget-object v1, p0, Lcom/example/shop/ui/MainActivity;->addButton:Landroid/widget/Button;

    const-string v0, "Add to cart"

    invoke-virtual {v1, v0}, Landroid/widget/Button;->setText(Ljava/lang/CharSequence;)V

    return-void
.end method
