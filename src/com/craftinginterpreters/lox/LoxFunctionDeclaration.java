package com.craftinginterpreters.lox;

import java.util.List;

public interface LoxFunctionDeclaration {
  String name();
  List<Token> params();
  List<Stmt> body();
}