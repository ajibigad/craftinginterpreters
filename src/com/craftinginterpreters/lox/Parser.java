package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.List;

import static com.craftinginterpreters.lox.TokenType.*;

class Parser {
  private static class ParseError extends RuntimeException {}

  private final List<Token> tokens;
  private int current = 0;
  private int blockDepth = 0;

  Parser(List<Token> tokens) {
    this.tokens = tokens;
  }
  List<Stmt> parse() {
    List<Stmt> statements = new ArrayList<>();
    while (!isAtEnd()) {
      statements.add(declaration());
    }

    return statements; // [parse-error-handling]
  }
  private Expr expression() {
    return assignment();
  }
  private Stmt declaration() {
    try {
      if (match(VAR)) return varDeclaration();

      return statement();
    } catch (ParseError error) {
      synchronize();
      // System.out.printf("Sync point: %s on line %d\n", peek(), peek().line);
      return null;
    }
  }
  private Stmt statement() {
    if (match(PRINT)) return printStatement();
    // LEFT_BRACE EOL* statement* RIGHT_BRACE stmt_terminator
    if (match(LEFT_BRACE)) {
      blockDepth++;
      while (match(EOL)) {}
      return new Stmt.Block(block());
    }

    return expressionStatement();
  }
  private List<Stmt> block() {
    List<Stmt> statements = new ArrayList<>();

    while (!check(RIGHT_BRACE) && !isAtEnd()) {
      Stmt statement = declaration();
      // if statement is null, it means a parse error was raised and the statement is not valid, 
      //  skip adding this statement to the block. it will be reported as a parse error
      if (statement != null) { 
        statements.add(statement);
      }
    }

    consume(RIGHT_BRACE, "Expect '}' after block.");
    consumeStmtTerminator("Expect statement terminator after block.");
    blockDepth--;
    return statements;
  }
  private Stmt printStatement() {
    Expr value = expression();
    consumeStmtTerminator("Expect ';' or EOL after value.");
    return new Stmt.Print(value);
  }
  private Stmt varDeclaration() {
    Token name = consume(IDENTIFIER, "Expect variable name.");

    Expr initializer = null;
    if (match(EQUAL)) {
      initializer = expression();
    }

    consumeStmtTerminator("Expect ';' or EOL after variable declaration.");
    return new Stmt.Var(name, initializer);
  }
  private Stmt expressionStatement() {
    Expr expr = expression();
    consumeStmtTerminator("Expect ';' or EOL after expression.");
    return new Stmt.Expression(expr);
  }
  private boolean matchStmtTerminator() {
    // stmt_terminator -> (EOF|EOL*|SEMICOLON (EOF?|EOL*))
    if (isAtEnd()) return true;
    if (match(EOL)) {
      while (match(EOL)) {}
      return true;
    }

    // SEMICOLON (EOF?|EOL*)
    if (match(SEMICOLON)) {
      if (isAtEnd()) {
        return true;
      } else if (peek().type == EOL) {
        while (match(EOL)) {}
        return true;
      }
      return true;
    }

    // if we are in a block, a right brace is a valid statement terminator
    if (check(RIGHT_BRACE) && blockDepth > 0) return true;

    return false;
  }
  private void consumeStmtTerminator(String message) {
    if (matchStmtTerminator()) return;
    throw error(peek(), message);
  }
  private Expr assignment() {
    Expr expr = equality();

    if (match(EQUAL)) {
      Token equals = previous();
      Expr value = assignment();

      if (expr instanceof Expr.Variable) {
        Token name = ((Expr.Variable)expr).name;
        return new Expr.Assign(name, value);
      }

      error(equals, "Invalid assignment target."); // [no-throw]
    }

    return expr;
  }
  private Expr equality() {
    Expr expr = comparison();

    while (match(BANG_EQUAL, EQUAL_EQUAL)) {
      Token operator = previous();
      Expr right = comparison();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }
  private Expr comparison() {
    Expr expr = term();

    while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
      Token operator = previous();
      Expr right = term();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }
  private Expr term() {
    Expr expr = factor();

    while (match(MINUS, PLUS)) {
      Token operator = previous();
      Expr right = factor();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }
  private Expr factor() {
    Expr expr = unary();

    while (match(SLASH, STAR)) {
      Token operator = previous();
      Expr right = unary();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }
  private Expr unary() {
    if (match(BANG, MINUS)) {
      Token operator = previous();
      Expr right = unary();
      return new Expr.Unary(operator, right);
    }

    return primary();
  }
  private Expr primary() {
    if (match(FALSE)) return new Expr.Literal(false);
    if (match(TRUE)) return new Expr.Literal(true);
    if (match(NIL)) return new Expr.Literal(null);

    if (match(NUMBER, STRING)) {
      return new Expr.Literal(previous().literal);
    }

    if (match(IDENTIFIER)) {
      return new Expr.Variable(previous());
    }

    if (match(LEFT_PAREN)) {
      Expr expr = expression();
      consume(RIGHT_PAREN, "Expect ')' after expression.");
      return new Expr.Grouping(expr);
    }

    throw error(peek(), "Expect expression.");
  }
  private boolean match(TokenType... types) {
    for (TokenType type : types) {
      if (check(type)) {
        advance();
        return true;
      }
    }

    return false;
  }
  private Token consume(TokenType type, String message) {
    if (check(type)) return advance();

    throw error(peek(), message);
  }
  private boolean check(TokenType type) {
    if (isAtEnd()) return false;
    return peek().type == type;
  }
  private Token advance() {
    if (!isAtEnd()) current++;
    return previous();
  }
  private boolean isAtEnd() {
    return peek().type == EOF;
  }

  private Token peek() {
    return tokens.get(current);
  }

  private Token previous() {
    return tokens.get(current - 1);
  }
  private ParseError error(Token token, String message) {
    Lox.error(token, message);
    return new ParseError();
  }
  private void synchronize() {
    // for case like 2+;1+2 we should synchronize to token "1" and resume parsing from there
    if (matchStmtTerminator()) return;

    // for case 1+2 3*8; 4+5; Sync to "4" and resume parsing from there
    advance();

    while (!isAtEnd()) {
      switch (peek().type) {
        case CLASS:
        case FUN:
        case VAR:
        case FOR:
        case IF:
        case WHILE:
        case PRINT:
        case RETURN:
          return;
      }

      if (matchStmtTerminator()) return;
      advance();
    }
  }
}
