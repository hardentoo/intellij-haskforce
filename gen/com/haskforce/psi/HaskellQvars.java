// This is a generated file. Not intended for manual editing.
package com.haskforce.psi;

import java.util.List;
import org.jetbrains.annotations.*;
import com.intellij.psi.PsiElement;

public interface HaskellQvars extends PsiElement {

  @NotNull
  List<HaskellQvar> getQvarList();

  @NotNull
  PsiElement getLparen();

  @Nullable
  PsiElement getRparen();

}