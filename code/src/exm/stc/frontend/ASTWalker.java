/*
 * Copyright 2013 University of Chicago and Argonne National Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package exm.stc.frontend;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import exm.stc.ast.FilePosition.LineMapping;
import exm.stc.ast.SwiftAST;
import exm.stc.ast.antlr.ExMParser;
import exm.stc.common.CompilerBackend.WaitMode;
import exm.stc.common.Logging;
import exm.stc.common.Settings;
import exm.stc.common.TclFunRef;
import exm.stc.common.exceptions.DoubleDefineException;
import exm.stc.common.exceptions.InvalidAnnotationException;
import exm.stc.common.exceptions.InvalidSyntaxException;
import exm.stc.common.exceptions.ModuleLoadException;
import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.exceptions.TypeMismatchException;
import exm.stc.common.exceptions.UndefinedFunctionException;
import exm.stc.common.exceptions.UndefinedTypeException;
import exm.stc.common.exceptions.UndefinedVarError;
import exm.stc.common.exceptions.UserException;
import exm.stc.common.exceptions.VariableUsageException;
import exm.stc.common.lang.Annotations;
import exm.stc.common.lang.Arg;
import exm.stc.common.lang.Constants;
import exm.stc.common.lang.ForeignFunctions;
import exm.stc.common.lang.ForeignFunctions.SpecialFunction;
import exm.stc.common.lang.ForeignFunctions.TclOpTemplate;
import exm.stc.common.lang.Intrinsics.IntrinsicFunction;
import exm.stc.common.lang.Operators.BuiltinOpcode;
import exm.stc.common.lang.Redirects;
import exm.stc.common.lang.TaskMode;
import exm.stc.common.lang.TaskProp.TaskPropKey;
import exm.stc.common.lang.TaskProp.TaskProps;
import exm.stc.common.lang.Types;
import exm.stc.common.lang.Types.ArrayInfo;
import exm.stc.common.lang.Types.ArrayType;
import exm.stc.common.lang.Types.ExprType;
import exm.stc.common.lang.Types.FileKind;
import exm.stc.common.lang.Types.FunctionType;
import exm.stc.common.lang.Types.RefType;
import exm.stc.common.lang.Types.StructType;
import exm.stc.common.lang.Types.UnionType;
import exm.stc.common.lang.Types.StructType.StructField;
import exm.stc.common.lang.Types.SubType;
import exm.stc.common.lang.Types.Type;
import exm.stc.common.lang.Var;
import exm.stc.common.lang.Var.Alloc;
import exm.stc.common.lang.Var.DefType;
import exm.stc.common.util.Pair;
import exm.stc.common.util.StringUtil;
import exm.stc.common.util.TernaryLogic.Ternary;
import exm.stc.frontend.Context.FnProp;
import exm.stc.frontend.VariableUsageInfo.VInfo;
import exm.stc.frontend.tree.ArrayRange;
import exm.stc.frontend.tree.Assignment;
import exm.stc.frontend.tree.ForLoopDescriptor;
import exm.stc.frontend.tree.ForLoopDescriptor.LoopVar;
import exm.stc.frontend.tree.ForeachLoop;
import exm.stc.frontend.tree.FunctionDecl;
import exm.stc.frontend.tree.If;
import exm.stc.frontend.tree.IterateDescriptor;
import exm.stc.frontend.tree.LValue;
import exm.stc.frontend.tree.Literals;
import exm.stc.frontend.tree.Switch;
import exm.stc.frontend.tree.Update;
import exm.stc.frontend.tree.VariableDeclaration;
import exm.stc.frontend.tree.VariableDeclaration.VariableDescriptor;
import exm.stc.frontend.tree.Wait;
import exm.stc.ic.STCMiddleEnd;
/**
 * This class walks the Swift AST.
 * It performs typechecking and dataflow analysis as it goes
 *
 */
public class ASTWalker {

  private STCMiddleEnd backend;
  private VarCreator varCreator = null;
  private ExprWalker exprWalker = null;
  private VariableUsageAnalyzer varAnalyzer = null;
  private WrapperGen wrapper = null;

  /** Map of canonical name to input file for all already loaded */
  private Map<String, SwiftModule> loadedModuleMap = 
                        new HashMap<String, SwiftModule>();
  
  /** List of modules in order of inclusion */
  private List<ModuleInfo> loadedModules = new ArrayList<ModuleInfo>();
  
  /** 
   * Set of input files that have been compiled (or are in process of being
   * compiled.
   */
  private Set<String> compiledInputFiles = new HashSet<String>();
  
  /** Stack of input files.  Top of stack is one currently processed */
  private Deque<SwiftModule> inputFiles = new ArrayDeque<SwiftModule>();
  
  /** Line mapping for current input file */
  private LineMapping lineMapping;

  private static enum FrontendPass {
    DEFINITIONS, // Process top level defs
    COMPILE,     // Compile functions
  }
  
  
  public ASTWalker() {
  }


  /**
   * Walk the AST and make calls to backend to generate lower level code.
   * This function is called to start the walk at the top level file
   * @param backend
   * @param tree
   * @throws UserException
   */
  public void walk(STCMiddleEnd backend, SwiftModule mainModule) 
          throws UserException {
    this.backend = backend;
    this.varCreator = new VarCreator(backend);
    this.wrapper = new WrapperGen(backend);
    this.exprWalker = new ExprWalker(wrapper, varCreator, backend,
                                     mainModule.lineMapping);
    this.varAnalyzer = new VariableUsageAnalyzer();
    GlobalContext context = new GlobalContext(mainModule.inputFilePath,
                                              Logging.getSTCLogger());
    SwiftAST ast = mainModule.ast;
    // Dump ANTLR's view of the SwiftAST (unsightly):
    // if (logger.isDebugEnabled())
    // logger.debug("tree: \n" + tree.toStringTree());

    // Use our custom printTree
    if (LogHelper.isDebugEnabled())
      LogHelper.debug(context, ast.printTree());

    // Assume root module for now (TODO)
    ModuleInfo mainInfo = new ModuleInfo(mainModule.inputFilePath, "");
    ModuleInfo builtins = createModuleInfo(context, Arrays.asList("builtins"));
    
    // Two passes: first to find definitions, second to compile functions
    loadModule(context, FrontendPass.DEFINITIONS, builtins);
    walkFile(context, mainInfo, mainModule, FrontendPass.DEFINITIONS);
    for (ModuleInfo loadedModule: loadedModules) {
      loadModule(context, FrontendPass.COMPILE, loadedModule);
    }
    
    FunctionType fn = context.lookupFunction(Constants.MAIN_FUNCTION);
    if (fn == null || 
        !context.hasFunctionProp(Constants.MAIN_FUNCTION, FnProp.COMPOSITE)) {
      throw new UndefinedFunctionException(context,
          "No composite main function was defined in the script");
    }
  }


  /**
   * Called to walk an input file
   * @param context
   * @param ast
   * @throws UserException
   */
  private void walkFile(GlobalContext context, 
        ModuleInfo module, SwiftModule parsed,
        FrontendPass pass)
      throws UserException {
    if (pass == FrontendPass.DEFINITIONS) {
      assert(!loadedModuleMap.containsKey(module.canonicalName));
      loadedModuleMap.put(module.canonicalName, parsed);
      loadedModules.add(module);
    }
    
    LogHelper.debug(context, "Entered module " + module.canonicalName
               + " on pass " + pass);
    filePush(parsed);
    walkTopLevel(context, parsed.ast, pass);
    filePop();
    LogHelper.debug(context, "Finishing module" + module.canonicalName
               + " for pass " + pass);
  }

  /**
   * Called when starting to process new input file
   * @param parsed
   */
  private void filePush(SwiftModule parsed) {
    inputFiles.push(parsed);
    updateCurrentFile();
  }

  /**
   * Called when finished processing input file
   */
  private void filePop() {
    inputFiles.pop();
    updateCurrentFile();
  }
  
  private void updateCurrentFile() {
    if (inputFiles.isEmpty()) {
      lineMapping = null;
    } else {
      lineMapping = inputFiles.peek().lineMapping;
    }
  }


  private void walkTopLevel(GlobalContext context, SwiftAST fileTree,
      FrontendPass pass) throws UserException {
    if (pass == FrontendPass.DEFINITIONS) {
      walkTopLevelDefs(context, fileTree);
    } else {
      assert(pass == FrontendPass.COMPILE);
      walkTopLevelCompile(context, fileTree);
    }
  }

  /**
   * First pass:
   *  - Register (but don't compile) all functions and other definitions
   * @param context
   * @param fileTree
   * @throws UserException
   * @throws DoubleDefineException
   * @throws UndefinedTypeException
   */
  private void walkTopLevelDefs(GlobalContext context, SwiftAST fileTree)
      throws UserException, DoubleDefineException, UndefinedTypeException {
    assert(fileTree.getType() == ExMParser.PROGRAM);
    context.syncFilePos(fileTree, lineMapping);
    
    for (SwiftAST topLevelDefn: fileTree.children()) {
      int type = topLevelDefn.getType();
      context.syncFilePos(topLevelDefn, lineMapping);
      switch (type) {
      case ExMParser.IMPORT:
        importModule(context, topLevelDefn, FrontendPass.DEFINITIONS);
        break;
        
      case ExMParser.DEFINE_BUILTIN_FUNCTION:
        defineBuiltinFunction(context, topLevelDefn);
        break;
  
      case ExMParser.DEFINE_FUNCTION:
        defineFunction(context, topLevelDefn);
        break;
  
      case ExMParser.DEFINE_APP_FUNCTION:
        defineAppFunction(context, topLevelDefn);
        break;
  
      case ExMParser.DEFINE_NEW_STRUCT_TYPE:
        defineNewStructType(context, topLevelDefn);
        break;
        
      case ExMParser.DEFINE_NEW_TYPE:
      case ExMParser.TYPEDEF:
        defineNewType(context, topLevelDefn, type == ExMParser.TYPEDEF);
        break;
        
      case ExMParser.GLOBAL_CONST:
        globalConst(context, topLevelDefn);
        break;
      
      case ExMParser.EOF:
        endOfFile(context, topLevelDefn);
        break;
  
      default:
        String name = LogHelper.tokName(type);
        throw new STCRuntimeError("Unexpected token: " + name
            + " at program top level");
      }
    }
  }


  /**
   * Second pass:
   *  - Compile composite and app functions, now that all function names are known
   * @param context
   * @param fileTree
   * @throws UserException
   */
  private void walkTopLevelCompile(GlobalContext context, SwiftAST fileTree)
      throws UserException {
    assert(fileTree.getType() == ExMParser.PROGRAM);
    context.syncFilePos(fileTree, lineMapping);
    // Second pass to compile functions
    for (int i = 0; i < fileTree.getChildCount(); i++) {
      SwiftAST topLevelDefn = fileTree.child(i);
      context.syncFilePos(topLevelDefn, lineMapping);
      int type = topLevelDefn.getType();
      switch (type) {
      case ExMParser.IMPORT:
        // Don't recurse: we invoke compilation of modules elsewher
        break;
        
      case ExMParser.DEFINE_FUNCTION:
        compileFunction(context, topLevelDefn);
        break;

      case ExMParser.DEFINE_APP_FUNCTION:
        compileAppFunction(context, topLevelDefn);
        break;
      }
    }
  }

  private static class ModuleInfo {
    public final String filePath;
    public final String canonicalName;
    
    public ModuleInfo(String filePath, String canonicalName) {
      super();
      this.filePath = filePath;
      this.canonicalName = canonicalName;
    }
  }

  private void importModule(GlobalContext context, SwiftAST tree,
      FrontendPass pass) throws UserException {
    assert(tree.getType() == ExMParser.IMPORT);
    assert(tree.getChildCount() == 1);
    SwiftAST moduleID = tree.child(0);
    
    ModuleInfo module = parseModuleName(context, moduleID);
    
    loadModule(context, pass, module);
  }


  private void loadModule(GlobalContext context, FrontendPass pass,
      ModuleInfo module) throws ModuleLoadException, UserException {
    SwiftModule parsed;
    boolean alreadyLoaded;
    if (loadedModuleMap.containsKey(module.canonicalName)) {
      alreadyLoaded = true;
      parsed = loadedModuleMap.get(module.canonicalName);
    } else {
      alreadyLoaded = false;
      // Load the file
      try {
        parsed = SwiftModule.parse(module.filePath, false);
      } catch (IOException e) {
        throw new ModuleLoadException(context, module.filePath, e);
      }
    }
    
    // Now file is parsed, we decide how to handle import
    if (pass == FrontendPass.DEFINITIONS) {
      // Don't reload definitions
      if (!alreadyLoaded) {
        walkFile(context, module, parsed, pass);
      }
    } else {
      assert(pass == FrontendPass.COMPILE);
      // Should have been loaded at defs stage
      assert(alreadyLoaded);
      // Check if already being compiled
      if (!compiledInputFiles.contains(module.canonicalName)) {
        walkFile(context, module, parsed, pass);
      }
    }
  }

  private ModuleInfo parseModuleName(Context context, SwiftAST moduleID)
      throws InvalidSyntaxException, ModuleLoadException {
    List<String> modulePath;
    if (moduleID.getType() == ExMParser.STRING) {
      // Forms:  
      //   module      => ./module.swift
      //   pkg/module  => pkg/module.swift
      // Implicit .swift extension added.  Relative to module search path
      String path = Literals.extractLiteralString(context, moduleID);
      modulePath = new ArrayList<String>();
      for (String elem: path.split("/+")) {
        modulePath.add(elem);
      }
    } else {
      assert(moduleID.getType() == ExMParser.IMPORT_PATH);
      // Forms:
      // pkg        => ./module.swift
      // pkg.module => pkg/module.swift
      modulePath = new ArrayList<String>();
      for (SwiftAST idT: moduleID.children()) {
        assert(idT.getType() == ExMParser.ID);
        modulePath.add(idT.getText());
      }
    }
    return createModuleInfo(context, modulePath);
  }


  private static String moduleCanonicalName(List<String> modulePath) {
    String canonicalName = "";
    boolean first = true;
    for (String component: modulePath) {
      if (first) {
        first = false;
      } else {
        canonicalName += ".";
      }
      canonicalName += component;
    }
    return canonicalName;
  }

  private ModuleInfo createModuleInfo(Context context, List<String> modulePath)
      throws ModuleLoadException {
    String canonicalName = moduleCanonicalName(modulePath);
    String filePath = locateModule(context, canonicalName, modulePath);
    return new ModuleInfo(filePath, canonicalName);
  }


  private String locateModule(Context context, String moduleName,
                              List<String> modulePath) throws ModuleLoadException {
    for (String searchDir: Settings.getModulePath()) {
      if (searchDir.length() == 0) {
        continue;
      }
      String currDir = searchDir;
      // Find right subdirectory
      for (String subDir: modulePath.subList(0, modulePath.size() - 1)) {
        currDir = currDir + File.separator + subDir;
      }
      String fileName = modulePath.get(modulePath.size() - 1) + ".swift";
      String filePath = currDir + File.separator + fileName;
      
      if (new File(filePath).isFile()) {
        LogHelper.debug(context, "Resolved " + moduleName + " to " + filePath);
        return filePath;
      }
    }
    
    throw new ModuleLoadException(context, "Could not find module " + moduleName + 
                  " in search path: " + Settings.getModulePath().toString());
  }

  /**
   * Control what statement walker should process
   */
  private static enum WalkMode {
    NORMAL,
    ONLY_DECLARATIONS, // Only process variable declarations
    ONLY_EVALUATION, // Process everything but declarations 
  }

  /**
   * Walk a tree that is a procedure statement.
   *
   * @param context
   * @param tree
   * @param walkMode mode to evaluate statements in
   * @param blockVu
   * @return "results" of statement that are blocked on in event
   *         of chaining
   * @throws UserException
   */
  private List<Var> walkStatement(Context context, SwiftAST tree, WalkMode walkMode)
  throws UserException
  {
      int token = tree.getType();
      context.syncFilePos(tree, lineMapping);
      
      
      if (walkMode == WalkMode.ONLY_DECLARATIONS) { 
        if (token == ExMParser.DECLARATION){
          return declareVariables(context, tree, walkMode);
        } else if (token == ExMParser.ASSIGN_EXPRESSION) {
          return assignExpression(context, tree, walkMode);
        } else {
          // Don't process non-variable-declaration statements
          return null;
        }
      }
      
      switch (token) {
        case ExMParser.BLOCK:
          // Create a local context (stack frame) for this nested block
          LocalContext nestedContext = new LocalContext(context);
          // Set up nested stack frame

          backend.startNestedBlock();
          backend.addComment("start of block@" + context.getFileLine());
          block(nestedContext, tree);
          backend.addComment("end of block@" + context.getFileLine());
          backend.endNestedBlock();
          break;

        case ExMParser.IF_STATEMENT:
          ifStatement(context, tree);
          break;

        case ExMParser.SWITCH_STATEMENT:
          switchStatement(context, tree);
          break;

        case ExMParser.DECLARATION:
          return declareVariables(context, tree, walkMode);

        case ExMParser.ASSIGN_EXPRESSION:
          return assignExpression(context, tree, walkMode);

        case ExMParser.EXPR_STMT:
          return exprStatement(context, tree);

        case ExMParser.FOREACH_LOOP:
          foreach(context, tree);
          break;
        
        case ExMParser.FOR_LOOP:
          forLoop(context, tree);
          break;
          
        case ExMParser.ITERATE:
          iterate(context, tree);
          break;
          
        case ExMParser.WAIT_STATEMENT:
          waitStmt(context, tree);
          break;
          
        case ExMParser.UPDATE:
          updateStmt(context, tree);
          break;
          
        case ExMParser.STATEMENT_CHAIN:
          stmtChain(context, tree);
          break;
          
        default:
          throw new STCRuntimeError
          ("Unexpected token type for statement: " +
              LogHelper.tokName(token));
      }
      // default is that statement has no output results
      return null;
  }

  private void stmtChain(Context context, SwiftAST tree) throws UserException {
    assert(tree.getType() == ExMParser.STATEMENT_CHAIN);
    
    // Evaluate multiple chainings iteratively
    
    // list of statements being waited on 
    List<SwiftAST> stmts = new ArrayList<SwiftAST>();
    while (tree.getType() == ExMParser.STATEMENT_CHAIN) {
      assert(tree.getChildCount() == 2);
      stmts.add(tree.child(0));
      tree = tree.child(1);
    }
    
    // final statement in chain
    SwiftAST finalStmt = tree;
    // result futures of last statement 
    List<Var> stmtResults = null; 
    
    // Process declarations for outer block
    for (SwiftAST stmt: stmts) {
      walkStatement(context, stmt, WalkMode.ONLY_DECLARATIONS);
    }
    walkStatement(context, finalStmt, WalkMode.ONLY_DECLARATIONS);
    
    // Evaluate statements into nested waits
    for (SwiftAST stmt: stmts) {
      stmtResults = walkStatement(context, stmt, WalkMode.ONLY_EVALUATION);
      if (stmtResults == null || stmtResults.isEmpty()) {
        context.syncFilePos(stmt, lineMapping);
        throw new UserException(context, "Tried to wait for result"
            + " of statement of type " + LogHelper.tokName(stmt.getType())
            + " but statement doesn't have output future to wait on");
      }
      
      String waitName = context.getFunctionContext().constructName("chain");
      backend.startWaitStatement(waitName, stmtResults, WaitMode.WAIT_ONLY,
             true, false, TaskMode.LOCAL);
    }
    
    // Evaluate the final statement
    walkStatement(context, finalStmt, WalkMode.ONLY_EVALUATION);
    
    // Close all waits
    for (int i = 0; i < stmts.size(); i++) {
      backend.endWaitStatement();
    }
  }


  private void waitStmt(Context context, SwiftAST tree) 
                                  throws UserException {
    Wait wait = Wait.fromAST(context, tree);
    ArrayList<Var> waitEvaled = new ArrayList<Var>();
    for (SwiftAST expr: wait.getWaitExprs()) {
      Type waitExprType = TypeChecker.findSingleExprType(context, expr);
      if (Types.isUnion(waitExprType)) {
        // Choose first alternative type
        for (Type alt: UnionType.getAlternatives(waitExprType)) {
          if (Types.canWaitForFinalize(alt)) {
            waitExprType = alt;
            break;
          }
        }
      }
      if (!Types.canWaitForFinalize(waitExprType)) {
        throw new TypeMismatchException(context, "Waiting for type " +
            waitExprType.typeName() + " is not supported");
      }
      Var res = exprWalker.eval(context, expr, waitExprType, false, null);
      waitEvaled.add(res);
    }
    
    ArrayList<Var> keepOpenVars = new ArrayList<Var>();
    summariseBranchVariableUsage(context, 
          Arrays.asList(wait.getBlock().getVariableUsage()), keepOpenVars);
    
    
    // Quick sanity check to see we're not directly blocking
    // on any arrays written inside
    HashSet<String> waitVarSet = 
        new HashSet<String>(Var.nameList(waitEvaled));
    waitVarSet.retainAll(Var.nameList(keepOpenVars));
    if (waitVarSet.size() > 0) {
      throw new UserException(context, 
          "Deadlock in wait statement. The following arrays are written "
        + "inside the body of the wait: " + waitVarSet.toString());
    }
    
    backend.startWaitStatement(
          context.getFunctionContext().constructName("explicitwait"),
                      waitEvaled,
                      WaitMode.WAIT_ONLY, true, false, TaskMode.LOCAL_CONTROL);
    block(new LocalContext(context), wait.getBlock());
    backend.endWaitStatement();
  }
  
  /**
   * block operates on a BLOCK node of the AST. This should be called for every
   * logical code block (e.g. function bodies, condition bodies, etc) in the
   * program
   *
   * @param context a new context for this block
   */
  private void block(Context context, SwiftAST tree) throws UserException {
    LogHelper.trace(context, "block start");

    if (tree.getType() != ExMParser.BLOCK) {
      throw new STCRuntimeError("Expected to find BLOCK token" + " at "
          + tree.getLine() + ":" + tree.getCharPositionInLine());
    }

    for (SwiftAST stmt: tree.children()) {
      walkStatement(context, stmt, WalkMode.NORMAL);
    }

    LogHelper.trace(context, "block done");
  }

  private void ifStatement(Context context, SwiftAST tree)
      throws UserException {    
    LogHelper.trace(context, "if...");
    If ifStmt = If.fromAST(context, tree); 
    
    
    // Condition must be boolean and stored to be fetched later
    Var conditionVar = exprWalker.eval(context,
        ifStmt.getCondition(), ifStmt.getCondType(context),
        false, null);
    assert (conditionVar != null);
    VariableUsageInfo thenVU = ifStmt.getThenBlock().checkedGetVariableUsage();

    List<VariableUsageInfo> branchVUs;
    if (ifStmt.hasElse()) {
      VariableUsageInfo elseVU = ifStmt.getElseBlock()
                                    .checkedGetVariableUsage();
      branchVUs = Arrays.asList(thenVU, elseVU);
    } else {
      branchVUs = Arrays.asList(thenVU);
    }

    // Check that condition var isn't assigned inside block - would be deadlock
    checkConditionalDeadlock(context, conditionVar, branchVUs);
    
    FunctionContext fc = context.getFunctionContext();
    backend.startWaitStatement( fc.constructName("if"), 
              Arrays.asList(conditionVar),
                WaitMode.WAIT_ONLY, false, false, TaskMode.LOCAL_CONTROL);

    Context waitContext = new LocalContext(context);
    Var condVal = varCreator.fetchValueOf(waitContext, conditionVar);
    backend.startIfStatement(Arg.createVar(condVal), ifStmt.hasElse());
    block(new LocalContext(waitContext), ifStmt.getThenBlock());

    if (ifStmt.hasElse()) {
      backend.startElseBlock();
      block(new LocalContext(waitContext), ifStmt.getElseBlock());
    }
    backend.endIfStatement();
    backend.endWaitStatement();
  }

  /**
   * Check for deadlocks of the form:
   * if (a) {
   *   a = 3;
   * } else {
   *   a = 2;
   * }
   * We should not allow any code to be compiled in which a variable is inside
   * a conditional statement for each is is the condition
   * TODO: this is a very limited form of deadlock detection.  In
   *      general we need to check the full variable dependency chain to make
   *      sure that the variable in the conditional statement isn't dependent
   *      at all on anything inside the condition
   * @param context
   * @param conditionVar
   * @param branchVU
   * @throws VariableUsageException
   */
  private void checkConditionalDeadlock(Context context, Var conditionVar,
      List<VariableUsageInfo> branchVUs) throws VariableUsageException {
    for (VariableUsageInfo branchVU: branchVUs) {
      assert(branchVU != null);
      VInfo vinfo = branchVU.lookupVariableInfo(conditionVar.name());
      if (vinfo != null && vinfo.isAssigned() != Ternary.FALSE) {
        throw new VariableUsageException(context, "Deadlock on " +
            conditionVar.name() + ", var is assigned inside conditional"
            + " branch for which it is the condition");
      }
    }
  }

  /**
   *
   * @param context
   * @param branchVUs
   *          The variable usage info for all branches
   * @param writtenVars
   *          All vars that might be written are added here
   * @throws UserException
   * @throws UndefinedTypeException
   */
  private void summariseBranchVariableUsage(Context context,
      List<VariableUsageInfo> branchVUs, List<Var> writtenVars)
          throws UndefinedTypeException, UserException {
    for (Var v : context.getVisibleVariables()) {
      // see if it is an array that might be modified
      if (Types.isArray(v.type())) {
        for (VariableUsageInfo bvu : branchVUs) {
          VInfo vi = bvu.lookupVariableInfo(v.name());
          if (vi != null && vi.isAssigned() != Ternary.FALSE) {
            writtenVars.add(v);
            break;
          }
        }
      } else if (Types.isStruct(v.type())) {
        // Need to find arrays inside structs
        ArrayList<Pair<Var, VInfo>> arrs = new ArrayList<Pair<Var, VInfo>>();
        // This procedure might add the same array multiple times,
        // so use a set to avoid duplicates
        HashSet<Var> alreadyFound = new HashSet<Var>();
        for (VariableUsageInfo bvu : branchVUs) {
          arrs.clear();
          exprWalker.findArraysInStruct(context, v,
              bvu.lookupVariableInfo(v.name()), arrs);
          for (Pair<Var, VInfo> p: arrs) {
            if (p.val2.isAssigned() != Ternary.FALSE) {
              alreadyFound.add(p.val1);
            }
          }
        }
        writtenVars.addAll(alreadyFound);
      }
    }

  }

  private void switchStatement(Context context, SwiftAST tree)
       throws UserException {
    LogHelper.trace(context, "switch...");    
    
    // Evaluate into a temporary variable. Only int supported now
    
    Switch sw = Switch.fromAST(context, tree);
    sw.typeCheck(context);
    
    Var switchVar = exprWalker.eval(context, sw.getSwitchExpr(), Types.F_INT,
                                    true, null);

    List<VariableUsageInfo> branchVUs = new ArrayList<VariableUsageInfo>();
    for (SwiftAST b : sw.getCaseBodies()) {
      branchVUs.add(b.checkedGetVariableUsage());
    }

    checkConditionalDeadlock(context, switchVar, branchVUs);

    // Generate all of the code
    FunctionContext fc = context.getFunctionContext();
    backend.startWaitStatement( fc.constructName("switch"),
                Arrays.asList(switchVar),
                WaitMode.WAIT_ONLY, false, false, TaskMode.LOCAL_CONTROL);

    Context waitContext = new LocalContext(context);
    Var switchVal = varCreator.createValueOfVar(waitContext,
                                                     switchVar); 

    backend.retrieveInt(switchVal, switchVar);

    LogHelper.trace(context, "switch: " + 
            sw.getCaseBodies().size() + " cases");
    backend.startSwitch(Arg.createVar(switchVal), sw.getCaseLabels(),
                                                         sw.hasDefault());
    for (SwiftAST caseBody : sw.getCaseBodies()) {
      block(new LocalContext(waitContext), caseBody);
      backend.endCase();
    }
    backend.endSwitch();
    backend.endWaitStatement();
  }

  private void foreach(Context context, SwiftAST tree) throws UserException {
    ForeachLoop loop = ForeachLoop.fromAST(context, tree); 
    
    if (loop.iteratesOverRange() && loop.getCountVarName() == null) {
      foreachRange(context, loop);
    } else {
      foreachArray(context, loop);
    }
  }
  /**
   * Handle the special case of a foreach loop where we are looping over range
   * specified by two or three integer parameters
   * @param context
   * @param loop
   * @throws UserException
   * @throws UndefinedTypeException
   */
  private void foreachRange(Context context, ForeachLoop loop) 
                                          throws UserException {
    ArrayRange range = ArrayRange.fromAST(context, loop.getArrayVarTree());
    range.typeCheck(context);
    
    /* Just evaluate all of the expressions into futures and rely
     * on constant folding in IC to clean up where possible
     */ 
    Var start = exprWalker.eval(context, range.getStart(), Types.F_INT, false, null);
    Var end = exprWalker.eval(context, range.getEnd(), Types.F_INT, false, null);
    Var step;
    if (range.getStep() != null) {
      step = exprWalker.eval(context, range.getStep(), Types.F_INT, false, null);
    } else {
      // Inefficient but constant folding will clean up
      step = varCreator.createTmp(context, Types.F_INT);
      backend.assignInt(step, Arg.createIntLit(1));
    }
    FunctionContext fc = context.getFunctionContext();
    int loopNum = fc.getCounterVal("foreach-range");
    
    // Need to pass in futures along with user vars
    List<Var> rangeBounds = Arrays.asList(start, end, step);
    backend.startWaitStatement(fc.getFunctionName() + "-wait-range" + loopNum,
             rangeBounds, WaitMode.WAIT_ONLY, false, false,
             TaskMode.LOCAL_CONTROL);
    Context waitContext = new LocalContext(context);
    Var startVal = varCreator.fetchValueOf(waitContext, start);
    Var endVal = varCreator.fetchValueOf(waitContext, end);
    Var stepVal = varCreator.fetchValueOf(waitContext, step);
    Context bodyContext = loop.setupLoopBodyContext(waitContext, true, false);
    
    // The per-iteration value of the range
    Var memberVal = varCreator.createValueOfVar(bodyContext,
                                            loop.getMemberVar(), false);
    Var counterVal = loop.getLoopCountVal();
    
    backend.startRangeLoop(fc.getFunctionName() + "-range" + loopNum,
            memberVal, counterVal,
            Arg.createVar(startVal), Arg.createVar(endVal), 
            Arg.createVar(stepVal),
            loop.getDesiredUnroll(), loop.getSplitDegree(),
            loop.getLeafDegree());
    // Need to spawn off task per iteration
    if (!loop.isSyncLoop()) {
      backend.startWaitStatement(fc.getFunctionName() + "range-iter" + loopNum,
          Arrays.<Var>asList(),
          WaitMode.TASK_DISPATCH, false, false, TaskMode.CONTROL);
    }
    
    // We have the current value, but need to put it in a future in case user
    //  code refers to it
    varCreator.initialiseVariable(bodyContext, loop.getMemberVar());
    backend.assignInt(loop.getMemberVar(), Arg.createVar(memberVal));
    if (loop.getCountVarName() != null) {
      Var loopCountVar = varCreator.createVariable(bodyContext,
          Types.F_INT, loop.getCountVarName(), Alloc.STACK,
          DefType.LOCAL_USER, null);
      backend.assignInt(loopCountVar, Arg.createVar(counterVal));
    }
    block(bodyContext, loop.getBody());
    if (!loop.isSyncLoop()) {
      backend.endWaitStatement();
    }
    backend.endRangeLoop();
    backend.endWaitStatement();
  }
  
  /**
   * Handle the general foreach loop where we are looping over array
   * @param context
   * @param loop
   * @throws UserException
   * @throws UndefinedTypeException
   */
  private void foreachArray(Context context, ForeachLoop loop)
      throws UserException, UndefinedTypeException {
    Var arrayVar = exprWalker.eval(context, loop.getArrayVarTree(), loop.findArrayType(context), true, null);

    VariableUsageInfo bodyVU = loop.getBody().checkedGetVariableUsage();
    List<Var> writtenVars = new ArrayList<Var>();
    summariseBranchVariableUsage(context, Arrays.asList(bodyVU), writtenVars);

    for (Var v: writtenVars) {
      if (v.equals(arrayVar)) {
        throw new STCRuntimeError("Array variable "
                  + v + " is written in the foreach loop "
                  + " it is the loop array for - currently this " +
                  "causes a deadlock due to technical limitations");
      }
    }
    
    // Need to get handle to real array before running loop
    FunctionContext fc = context.getFunctionContext();
    int loopNum = fc.getCounterVal("foreach-array");
    
    Var realArray;
    Context outsideLoopContext;
    if (Types.isArrayRef(arrayVar.type())) {
      // If its a reference, wrap a wait() around the loop call
      backend.startWaitStatement(
          fc.getFunctionName() + "-foreach-refwait" + loopNum,
          Arrays.asList(arrayVar),
          WaitMode.WAIT_ONLY, false, false, TaskMode.LOCAL_CONTROL);

      outsideLoopContext = new LocalContext(context);
      realArray = varCreator.createTmp(outsideLoopContext,
                              arrayVar.type().memberType(), false, true);
      backend.retrieveRef(realArray, arrayVar);
    } else {
      realArray = arrayVar;
      outsideLoopContext = context;
    }
    
    // Block on array
    backend.startWaitStatement(
        fc.getFunctionName() + "-foreach-wait" + loopNum,
        Arrays.asList(realArray),
        WaitMode.WAIT_ONLY, false, false, TaskMode.LOCAL_CONTROL);
    
    loop.setupLoopBodyContext(outsideLoopContext, false, false);
    Context loopBodyContext = loop.getBodyContext();

    backend.startForeachLoop(fc.getFunctionName() + "-foreach" + loopNum,
        realArray, loop.getMemberVar(), loop.getLoopCountVal(),
        loop.getSplitDegree(), loop.getLeafDegree(), true);
    // May need to spawn off each iteration as task - use wait for this
    if (!loop.isSyncLoop()) {
      backend.startWaitStatement(
          fc.getFunctionName() + "-foreach-spawn" + loopNum,
          Arrays.<Var>asList(),
          WaitMode.TASK_DISPATCH, false, false, TaskMode.CONTROL);
    }
    // If the user's code expects a loop count var, need to create it here
    if (loop.getCountVarName() != null) {
      Var loopCountVar = varCreator.createVariable(loop.getBodyContext(),
                                                   loop.createCountVar());
      exprWalker.assign(loopCountVar, Arg.createVar(loop.getLoopCountVal()));
    }
    
    block(loopBodyContext, loop.getBody());
    
    // Close spawn wait
    if (!loop.isSyncLoop()) {
      backend.endWaitStatement();
    }
    backend.endForeachLoop();

    // Wait for array
    backend.endWaitStatement();
    if (Types.isArrayRef(arrayVar.type())) {
      // Wait for array ref
      backend.endWaitStatement();
    }
  }

  private void forLoop(Context context, SwiftAST tree) throws UserException {
    ForLoopDescriptor forLoop = ForLoopDescriptor.fromAST(context, tree);
    
    // Evaluate initial values of loop vars
    List<Var> initVals = evalLoopVarExprs(context, forLoop, 
                                                  forLoop.getInitExprs());
    
    FunctionContext fc = context.getFunctionContext();
    int loopNum = fc.getCounterVal("forloop");
    String loopName = fc.getFunctionName() + "-forloop-" + loopNum;
    
    HashMap<String, Var> parentLoopVarAliases = 
        new HashMap<String, Var>();
    for (LoopVar lv: forLoop.getLoopVars()) {
      if (lv.declaredOutsideLoop) {
        // Need to copy over value of loop variable on last iteration
        Var parentAlias = 
            varCreator.createVariable(context, lv.var.type(),
                  Var.OUTER_VAR_PREFIX + lv.var.name(),
                  Alloc.ALIAS, DefType.LOCAL_COMPILER,
                  lv.var.mapping());
        // Copy turbine ID
        backend.makeAlias(parentAlias, lv.var);
        parentLoopVarAliases.put(lv.var.name(), parentAlias);
      }
    }
    
    // Create context with loop variables
    Context loopIterContext = forLoop.createIterationContext(context);
    forLoop.validateCond(loopIterContext);
    Type condType = TypeChecker.findSingleExprType(loopIterContext, 
                                              forLoop.getCondition());

    // Evaluate the conditional expression for the first iteration outside the
    // loop, directly using temp names for loop variables
    HashMap<String, String> initRenames = new HashMap<String, String>();
    for (int i = 0; i < forLoop.loopVarCount(); i++) {
      initRenames.put(forLoop.getLoopVars().get(i).var.name(), 
            initVals.get(i).name());
    }
    Var initCond = exprWalker.eval(context, forLoop.getCondition(), condType, true, initRenames);
    
    // Start the loop construct with some initial values
    Var condArg = 
        loopIterContext.declareVariable(condType, Var.LOOP_COND_PREFIX + 
            loopNum, Alloc.TEMP, DefType.INARG, null);



    /* Pack the variables into vectors with the first element the condition */
    ArrayList<Var> loopVars = new ArrayList<Var>(forLoop.loopVarCount() + 1);
    loopVars.add(condArg);
    loopVars.addAll(forLoop.getUnpackedLoopVars());
    List<Boolean> definedHere = new ArrayList<Boolean>(forLoop.loopVarCount() + 1);
    definedHere.add(true); // Condition defined in construct
    for (LoopVar lv: forLoop.getLoopVars()) {
      definedHere.add(!lv.declaredOutsideLoop);
    }
    
    List<Boolean> blockingVector = new ArrayList<Boolean>(loopVars.size());
    blockingVector.add(true); // block on condition
    blockingVector.addAll(forLoop.blockingLoopVarVector());
    
    initVals.add(0, initCond);
    
    backend.startLoop(loopName, loopVars, definedHere, initVals, 
                      blockingVector);
    
    // get value of condVar
    Var condVal = varCreator.fetchValueOf(loopIterContext, condArg);
    
    // branch depending on if loop should start
    backend.startIfStatement(Arg.createVar(condVal), true);
    
    // Create new context for loop body to execute when condition passes
    Context loopBodyContext = new LocalContext(loopIterContext);
    
    // If this iteration is good, run all of the stuff in the block
    block(loopBodyContext, forLoop.getBody());
    
    forLoop.validateUpdates(loopBodyContext);
    //evaluate update expressions
    List<Var> newLoopVars = evalLoopVarExprs(loopBodyContext, forLoop, 
                                                forLoop.getUpdateRules());
    
    HashMap<String, String> nextRenames = new HashMap<String, String>();
    for (int i = 0; i < forLoop.loopVarCount(); i++) {
      nextRenames.put(forLoop.getLoopVars().get(i).var.name(), 
            newLoopVars.get(i).name());
    }
    Var nextCond = exprWalker.eval(loopBodyContext, 
              forLoop.getCondition(), condType, true, nextRenames);
    newLoopVars.add(0, nextCond);
    backend.loopContinue(newLoopVars, blockingVector);
    backend.startElseBlock();
    // Terminate loop, clean up open arrays and copy out final vals 
    // of loop vars
    Context loopFinalizeContext = new LocalContext(loopIterContext);
    for (LoopVar lv: forLoop.getLoopVars()) {
      if (lv.declaredOutsideLoop) {
        exprWalker.copyByValue(loopFinalizeContext, 
            lv.var, parentLoopVarAliases.get(lv.var.name()), 
            lv.var.type());
      }
    }
    
    backend.loopBreak();
    backend.endIfStatement();
    
    // finish loop construct
    backend.endLoop();
  }
  
  private void iterate(Context context, SwiftAST tree) throws UserException {
    IterateDescriptor loop = IterateDescriptor.fromAST(context, tree);
    
    //TODO: this is a little funny since the condition expr might be of type int,
    //    but this will work for time being
    Var falseV = varCreator.createTmp(context, Types.F_BOOL);
    backend.assignBool(falseV, Arg.createBoolLit(false));
    
    Var zero = varCreator.createTmp(context, Types.F_INT);
    backend.assignInt(zero, Arg.createIntLit(0));
    
    FunctionContext fc = context.getFunctionContext();
    int loopNum = fc.getCounterVal("iterate");
    String loopName = fc.getFunctionName() + "-iterate-" + loopNum;

    Context iterContext = loop.createIterContext(context);
    
    // Start the loop construct with some initial values
    Var condArg = 
      iterContext.declareVariable(Types.F_BOOL, Var.LOOP_COND_PREFIX + 
            loopNum, Alloc.TEMP, DefType.INARG, null);
    
    List<Boolean> blockingVars = Arrays.asList(true, false);
    backend.startLoop(loopName, 
        Arrays.asList(condArg, loop.getLoopVar()), Arrays.asList(true, true),
        Arrays.asList(falseV, zero), blockingVars);
    
    // get value of condVar
    Var condVal = varCreator.fetchValueOf(iterContext, condArg); 
    
    backend.startIfStatement(Arg.createVar(condVal), true);
    backend.loopBreak();
    backend.startElseBlock();
    Context bodyContext = new LocalContext(iterContext);
    block(bodyContext, loop.getBody());
    
    // Check the condition type now that all loop body vars have been declared
    Type condType = TypeChecker.findSingleExprType(iterContext,
        loop.getCond());
    if (!condType.assignableTo(Types.F_BOOL)) {
      throw new TypeMismatchException(bodyContext, 
          "iterate condition had invalid type: " + condType.typeName());
    }
    
    Var nextCond = exprWalker.eval(bodyContext, loop.getCond(),
                                          Types.F_BOOL, false, null);
    
    Var nextCounter = varCreator.createTmp(bodyContext,
                                      Types.F_INT);
    Var one = varCreator.createTmp(bodyContext, Types.F_INT);

    backend.assignInt(one, Arg.createIntLit(1));
    backend.asyncOp(BuiltinOpcode.PLUS_INT, nextCounter, 
        Arrays.asList(Arg.createVar(loop.getLoopVar()), Arg.createVar(one)));
    
    backend.loopContinue(Arrays.asList(nextCond, nextCounter), blockingVars);

    backend.endIfStatement();
    backend.endLoop();
  }


  private ArrayList<Var> evalLoopVarExprs(Context context,
      ForLoopDescriptor forLoop, Map<String, SwiftAST> loopVarExprs)
      throws UserException {
    ArrayList<Var> results = new ArrayList<Var>(
                                                forLoop.loopVarCount() + 1);
    for (int i = 0; i < forLoop.loopVarCount(); i++) {
      Var v = forLoop.getLoopVars().get(i).var;
      Type argType = v.type();
      SwiftAST expr = loopVarExprs.get(v.name());
      Type exprType = TypeChecker.findSingleExprType(context, expr);
      exprType = TypeChecker.checkAssignment(context, exprType,
                                             argType,v.name());
      results.add(exprWalker.eval(context, expr, exprType, false, null));
    }
    return results;
  }



  
  private List<Var> declareVariables(Context context, SwiftAST tree, WalkMode walkMode)
          throws UserException {
    LogHelper.trace(context, "declareVariable...");
    assert(tree.getType() == ExMParser.DECLARATION);
    int count = tree.getChildCount();
    if (count < 2)
      throw new STCRuntimeError("declare_multi: child count < 2");
    VariableDeclaration vd =  VariableDeclaration.fromAST(context, 
                                                    tree);
    List<Var> assignedVars = new ArrayList<Var>();
    
    for (int i = 0; i < vd.count(); i++) {
      VariableDescriptor vDesc = vd.getVar(i);
      SwiftAST declTree = vd.getDeclTree(i);
      SwiftAST assignedExpr = vd.getVarExpr(i);
      
      Var var;
      if (walkMode == WalkMode.ONLY_EVALUATION) {
        var = context.lookupVarInternal(vDesc.getName());
      } else {
        var = declareVariable(context, vDesc);
      } 
      if (Types.isPrimUpdateable(var.type())) {
        if (walkMode == WalkMode.ONLY_DECLARATIONS) {
          throw new TypeMismatchException(context, var.name() +
                  " is an updateable and its declaration cannot be chained");  
        }
        // Have to init at declare time
        initUpdateableVar(context, var, assignedExpr);
      } else if (walkMode != WalkMode.ONLY_DECLARATIONS) {
         if (assignedExpr != null) {
           Assignment assignment = new Assignment(
                   Arrays.asList(new LValue(declTree, var)),
                   Arrays.asList(assignedExpr));
           assignedVars.addAll(assignMultiExpression(context, assignment, walkMode));
         }
      }
    }
    return assignedVars;
  }


  private void initUpdateableVar(Context context, Var var,
                                                SwiftAST initExpr) {
    if (initExpr != null) {
      // TODO
      // Handle as special case because currently we need an initial
      // value for the updateable variable right away
      if (var.type().equals(Types.UP_FLOAT)) {
        Double initVal = Literals.extractFloatLit(context, initExpr);
        if (initVal == null) {
          Long intLit = Literals.extractIntLit(context, initExpr);
          if (intLit != null) {
            initVal = Literals.interpretIntAsFloat(context, intLit);
          }
        } 
        if (initVal == null) {
          throw new STCRuntimeError("Don't yet support non-constant" +
                  " initialisers for updateable variables");
        }
        backend.initUpdateable(var, Arg.createFloatLit(initVal));
      } else {
        throw new STCRuntimeError("Non-float updateables not yet" +
                " implemented for type " + var.type());
      }
    } else {
      throw new STCRuntimeError("updateable variable " +
          var.name() + " must be given an initial value upon creation");
    }
  }

  private Var declareVariable(Context context,
      VariableDescriptor vDesc) throws UserException, UndefinedTypeException {
    Type definedType = vDesc.getType();

    Var mappedVar = null;
    // First evaluate the mapping expr
    if (vDesc.getMappingExpr() != null) {
      if (Types.isMappable(vDesc.getType())) {
        Type mapType = TypeChecker.findSingleExprType(context, 
                                          vDesc.getMappingExpr());
        if (!Types.isString(mapType)) {
          throw new TypeMismatchException(context, "Tried to map using " +
                  "non-string expression with type " + mapType.typeName());
        }
        mappedVar = exprWalker.eval(context, vDesc.getMappingExpr(), Types.F_STRING, false, null);
      } else {
        throw new TypeMismatchException(context, "Variable " + vDesc.getName()
                + " of type " + vDesc.getType().typeName() + " cannot be " +
                    " mapped");
      }
    }

    Var var = varCreator.createVariable(context, definedType, 
        vDesc.getName(), Alloc.STACK, DefType.LOCAL_USER, mappedVar);
    return var;
  }

  private List<Var> assignExpression(Context context, SwiftAST tree,
        WalkMode walkMode) throws UserException {
    LogHelper.debug(context, "assignment: ");
    LogHelper.logChildren(context.getLevel(), tree);
    
    Assignment assign = Assignment.fromAST(context, tree);
    return assignMultiExpression(context, assign, walkMode);
  }

  private List<Var> assignMultiExpression(Context context, Assignment assign,
      WalkMode walkMode) throws UserException, TypeMismatchException,
      UndefinedTypeException, UndefinedVarError {
    List<Var> multiAssignTargets = new ArrayList<Var>();
    for (Pair<List<LValue>, SwiftAST> pair: assign.getMatchedAssignments(context)) {
      List<LValue> lVals = pair.val1;
      SwiftAST rVal = pair.val2;
      List<Var> assignTargets = assignSingleExpr(context, lVals, rVal, walkMode);
      multiAssignTargets.addAll(assignTargets);
    }
    return multiAssignTargets;
  }

  private List<Var> assignSingleExpr(Context context, List<LValue> lVals,
      SwiftAST rValExpr, WalkMode walkMode) throws UserException, TypeMismatchException,
      UndefinedVarError, UndefinedTypeException {
    ExprType rValTs = Assignment.checkAssign(context, lVals, rValExpr);
    
    List<Var> result = new ArrayList<Var>(lVals.size());
    Deque<Runnable> afterActions = new LinkedList<Runnable>();
    boolean skipEval = false;
    // TODO: need to handle ambiguous input types
    for (int i = 0; i < lVals.size(); i++) {
      LValue lVal = lVals.get(i);
      Type rValType = rValTs.get(i);

      // Declare and initialize lval if not previously declared
      if (lVal.var == null) {
        // Should already have declared if only evaluating 
        assert(walkMode != WalkMode.ONLY_EVALUATION) : walkMode;
        LValue newLVal = lVal.varDeclarationNeeded(context, rValType);
        assert(newLVal != null && newLVal.var != null);
        varCreator.createVariable(context, newLVal.var);
        lVal = newLVal;
      }

      if (walkMode != WalkMode.ONLY_DECLARATIONS) {
        Type lValType = lVal.getType(context);
        
        String lValDesc = lVal.toString();
        Type rValConcrete = TypeChecker.checkAssignment(context, rValType,
                                                        lValType, lValDesc);
        backend.addComment("Swift l." + context.getLine() +
            ": assigning expression to " + lValDesc);
  
        // the variable we will evaluate expression into
        context.syncFilePos(lVal.tree, lineMapping);
        Var var = evalLValue(context, rValExpr, rValConcrete, lVal, 
                                                        afterActions);
        
        if (lVals.size() == 1 && rValExpr.getType() == ExMParser.VARIABLE) {
          /* Special case: 
           * A[i] = x;  
           * we just want x to be inserted into A without any temp variables being
           * created.  evalLvalue will do the insertion, and return the variable
           * represented by the LValue, but this happens to be x (because A[i] is 
           * now just an alias for x.  So we're done and can return!
           */
          String rValVar = rValExpr.child(0).getText();
          if (var.name().equals(rValVar)) {
            // LHS is just an alias for RHS.  This is ok if this is e.g.
            // A[i] = x; but not if it is x = x;
            if (lVal.indices.size() == 0) {
              throw new UserException(context, "Assigning var " + rValVar
                  + " to itself");
            }
            skipEval = true; 
            break;
          }
        }
        result.add(var);
      }
      
    }

    if (! skipEval && walkMode != WalkMode.ONLY_DECLARATIONS) {
      exprWalker.evalToVars(context, rValExpr, result, null);
    }
    
    for (Runnable action: afterActions) {
      action.run();
    }
    return result;
  }
  /**
   * Process an LValue for an assignment, resulting in a variable that
   * we can assign the RValue to
   * @param context
   * @param rValExpr
   * @param rValType type of the above expr
   * @param lval
   * @param afterActions sometimes the lvalue evaluation code wants to insert
   *                    code after the rvalue has been evaluated.  Any
   *                    runnables added to afterActions will be run after
   *                    the Rvalue is evaluated
   * @return the variable referred to by the LValue
   */
  private Var evalLValue(Context context, SwiftAST rValExpr,
      Type rValType, LValue lval, Deque<Runnable> afterActions)
      throws UndefinedVarError, UserException, UndefinedTypeException,
      TypeMismatchException {
    LValue arrayBaseLval = null; // Keep track of the root of the array
    LogHelper.trace(context, ("Evaluating lval " + lval.toString() + " with type " +
    lval.getType(context)));
    // Iteratively reduce the target until we have a scalar we can
    // assign to
    while (lval.indices.size() > 0) {
      if (lval.indices.get(0).getType() == ExMParser.STRUCT_PATH) {
        lval = reduceStructLVal(context, lval);
      } else {
        assert(lval.indices.get(0).getType() == ExMParser.ARRAY_PATH);
        if (arrayBaseLval == null) { 
            arrayBaseLval = lval;
        }
        assert(Types.isArray(arrayBaseLval.var.type()));
        lval = reduceArrayLVal(context, arrayBaseLval, lval, rValExpr, rValType,
                                afterActions);
        LogHelper.trace(context, "Reduced to lval " + lval.toString() + 
                " with type " + lval.getType(context));
      }
    }

    String varName = lval.varName;
    Var lValVar = context.lookupVarUser(varName);

    // Now if there is some mismatch between reference/value, rectify it
    return fixupRefValMismatch(context, rValType, lValVar);
  }


  /**
   * If there is a mismatch between lvalue and rvalue type in assignment
   * where one is a reference and one isn't, fix that up
   * @param context
   * @param rValType
   * @param lValVar
   * @return a replacement lvalue var if needed, or the original if types match
   * @throws UserException
   * @throws UndefinedTypeException
   */
  private Var fixupRefValMismatch(Context context, Type rValType,
      Var lValVar) throws UserException, UndefinedTypeException {
    if (rValType.assignableTo(lValVar.type())) {
      return lValVar;
    } else if (Types.isRef(lValVar.type())
            && rValType.assignableTo(lValVar.type())) {
      Var rValVar = varCreator.createTmp(context, rValType);
      backend.assignReference(lValVar, rValVar);
      return rValVar;
    } else if (Types.isRef(rValType) 
            && rValType.memberType().assignableTo(lValVar.type())) {
      Var rValVar = varCreator.createTmp(context, rValType);
      exprWalker.dereference(context, lValVar, rValVar);
      return rValVar;
    } else {
      throw new STCRuntimeError("Don't support assigning an "
          + "expression with type " + rValType.toString() + " to variable "
          + lValVar.toString() + " yet");
    }
  }


  /**
   * Processes part of an assignTarget path: the prefix
   * of struct lookups.  E.g. if we're trying to assign to
   * x.field.field.field[0], then this function handles the
   * 3x field lookups, making sure that a handle to
   * x.field.field.field is put into a temp variable, say called t0
   * This will then return a new assignment target for t0[0] for further
   * processing
   * @param context
   * @param lval
   * @return
   * @throws UserException
   * @throws UndefinedTypeException
   * @throws TypeMismatchException
   */
  private LValue reduceStructLVal(Context context,
      LValue lval) throws UserException, UndefinedTypeException,
      TypeMismatchException {
    // The variable at root of the current struct path
    Var rootVar = context.lookupVarUser(lval.varName);

    ArrayList<String> fieldPath = new ArrayList<String>();

    int structPathIndex = 0;
    while (structPathIndex < lval.indices.size() &&
        lval.indices.get(structPathIndex).getType() == ExMParser.STRUCT_PATH) {
      SwiftAST pathTree = lval.indices.get(structPathIndex);
      String fieldName = pathTree.child(0).getText();
      fieldPath.add(fieldName);
      structPathIndex++;
    }
    final int structPathLen = structPathIndex;

    Var curr = rootVar;
    for (int i = 0; i < structPathLen; i++) {
      List<String> currPath = fieldPath.subList(0, i+1);
      Var next = varCreator.createStructFieldTmp(context,
          rootVar, lval.getType(context, i+1), currPath, Alloc.ALIAS);

      backend.structLookup(next, curr, fieldPath.get(i));
      LogHelper.trace(context, "Lookup " + curr.name() + "." +
                               fieldPath.get(i));
      curr = next;
    }
    LValue newTarget = new LValue(lval.tree, curr,
        lval.indices.subList(structPathLen, lval.indices.size()));
    LogHelper.trace(context, "Transform target " + lval.toString() +
        "<" + lval.getType(context).toString() + "> to " +
        newTarget.toString() + "<" +
        newTarget.getType(context).toString() + "> by looking up " +
        structPathLen + " fields");
    return newTarget;
  }

  /** Handle a prefix of array lookups for the assign target
   * @param afterActions 
   * @throws UserException
   * @throws UndefinedTypeException
   * @throws TypeMismatchException */
  private LValue reduceArrayLVal(Context context, LValue origLval,
    LValue lval, SwiftAST rValExpr, Type rValType, Deque<Runnable> afterActions)
        throws TypeMismatchException, UndefinedTypeException, UserException {

    SwiftAST indexExpr = lval.indices.get(0);
    assert (indexExpr.getType() == ExMParser.ARRAY_PATH);
    assert (indexExpr.getChildCount() == 1);
    // Typecheck index expression
    Type indexType = TypeChecker.findSingleExprType(context, 
                                             indexExpr.child(0));
    if (!Types.isArrayKeyFuture(lval.var, indexType)) {
      throw new TypeMismatchException(context, 
          "Array key type mismatch in LVal.  " +
          "Expected: " + Types.arrayKeyType(lval.var) + " " +
          "Actual: " + indexType.typeName());
    }
    
    if (lval.indices.size() == 1) {
      Var lookedup = assignTo1DArray(context, origLval, lval, rValExpr, 
                                                      rValType, afterActions);
      return new LValue(lval.tree, lookedup, new ArrayList<SwiftAST>());
    } else {
      // multi-dimensional array handling: need to dynamically create subarray
      Var lvalArr = context.lookupVarUser(lval.varName);
      Type memberType = lval.getType(context, 1);
      Var mVar; // Variable for member we're looking up
      if (Types.isArray(memberType)) {

        Long literal = Literals.extractIntLit(context, indexExpr.child(0));
        if (literal != null) {
          long arrIx = literal;
          // Add this variable to array
          if (Types.isArray(lvalArr.type())) {
            mVar = varCreator.createTmpAlias(context, memberType);
            backend.arrayCreateNestedImm(mVar, lvalArr, 
                        Arg.createIntLit(arrIx));
          } else {
            assert(Types.isArrayRef(lvalArr.type()));
            mVar = varCreator.createTmp(context, 
                                  new RefType(memberType));
            backend.arrayRefCreateNestedImm(mVar, origLval.var, 
                lvalArr, Arg.createIntLit(arrIx));
          }

        } else {
          // Handle the general case where the index must be computed
          mVar = varCreator.createTmp(context, new RefType(memberType));
          Type keyType = Types.arrayKeyType(lvalArr);
          Var indexVar = exprWalker.eval(context, indexExpr.child(0), keyType,
                                         false, null);
          
          if (Types.isArray(lvalArr.type())) {
            backend.arrayCreateNestedFuture(mVar, lvalArr, indexVar);
          } else {
            assert(Types.isArrayRef(lvalArr.type()));
            backend.arrayRefCreateNestedFuture(mVar, origLval.var, lvalArr,
                                               indexVar);
          }
        }
      } else {
        /* 
         * Retrieve non-array member
         * must use reference because we might have to wait for the result to 
         * be inserted
         */
        mVar = varCreator.createTmp(context, new RefType(memberType));
      }

      return new LValue(lval.tree, mVar,
          lval.indices.subList(1, lval.indices.size()));
    }
}

  private Var assignTo1DArray(Context context, final LValue origLval,
      LValue lval, SwiftAST rvalExpr, Type rvalType,
      Deque<Runnable> afterActions)
      throws TypeMismatchException, UserException, UndefinedTypeException {
    assert (rvalExpr.getType() != ExMParser.ARRAY_PATH);
    assert(lval.indices.size() == 1);
    assert(Types.isArray(origLval.var.type()));
    final Var lvalVar;
    // Check that it is a valid array
    final Var arr = lval.var;

    Type arrType = arr.type();

    if (!Types.isArray(arrType) && !Types.isArrayRef(arrType)) {
      throw new TypeMismatchException(context, "Variable " + arr.name()
          + "is not an array, cannot index\n.");
    }
    final boolean isArrayRef = Types.isArrayRef(arrType);

    LogHelper.debug(context, 
            "Token type: " + LogHelper.tokName(rvalExpr.getType()));
    // Find or create variable to store expression result

    final boolean rvalIsRef = Types.isRef(rvalType);
    
    if (!rvalIsRef) {
      if (rvalExpr.getType() == ExMParser.VARIABLE) {
        // Get a handle to the variable, so we can just insert the variable
        //  directly into the array
        // This is a bit of a hack.  We return the rval as the lval and rely
        //  on the rest of the compiler frontend to treat the self-assignment
        //  as a no-op
        lvalVar = context.lookupVarUser(rvalExpr.child(0).getText());
      } else {
        // In other cases we need an intermediate variable
        Type arrMemberType;
        if (Types.isArray(arrType)) {
          arrMemberType = arrType.memberType();
        } else {
          assert(Types.isArrayRef(arrType));
          arrMemberType = arrType.memberType().memberType();
        }
        lvalVar = varCreator.createTmp(context, arrMemberType);
      }
    } else {
      //Rval is a ref, so create a new value of the dereferenced type and
      // rely on the compiler frontend later inserting instruction to
      // copy
      lvalVar = varCreator.createTmp(context, rvalType);
    }

    // We know what variable the result will go into now
    // Now need to work out the index and generate code to insert the
    // variable into the array

    SwiftAST indexTree = lval.indices.get(0);
    assert (indexTree.getType() == ExMParser.ARRAY_PATH);
    assert (indexTree.getChildCount() == 1);
    SwiftAST indexExpr = indexTree.child(0);


    final Var outermostArray = origLval.var;
    Type keyType = Types.arrayKeyType(arrType);
    
    Long literal = Literals.extractIntLit(context, indexExpr);

    if (Types.isInt(keyType) && literal != null) {
      final long arrIx = literal;
      // Add this variable to array
      afterActions.addFirst(new Runnable() {
        @Override
        public void run() {
          backendArrayInsert(arr, arrIx, lvalVar, isArrayRef, rvalIsRef,
              outermostArray);
        }});
    } else {
      // Handle the general case where the index must be computed
      final Var indexVar = exprWalker.eval(context, indexExpr, keyType, false, null);
      afterActions.addFirst(new Runnable() {
        @Override
        public void run() {
          backendArrayInsert(arr, indexVar, lvalVar, isArrayRef,
              rvalIsRef, outermostArray);
        }});
    }
    return lvalVar;
  }

  private void backendArrayInsert(final Var arr, long ix, Var member,
              boolean isArrayRef, boolean rvalIsRef, Var outermostArray) {
    if (isArrayRef && rvalIsRef) {
      // This should only be run when assigning to nested array
      backend.arrayRefDerefInsertImm(outermostArray, arr,
                         Arg.createIntLit(ix), member);
    } else if (isArrayRef && !rvalIsRef) {
      // This should only be run when assigning to nested array
      backend.arrayRefInsertImm(outermostArray, arr, Arg.createIntLit(ix),
                                member);
    } else if (!isArrayRef && rvalIsRef) {
      backend.arrayDerefInsertImm(arr, Arg.createIntLit(ix),  member);
    } else {
      assert(!isArrayRef && !rvalIsRef);
      backend.arrayInsertImm(arr, Arg.createIntLit(ix),  member);
    }
  }
  
  public void backendArrayInsert(Var arr, Var ix, Var member,
      boolean isArrayRef, boolean rvalIsRef, Var outermostArray) {
    if (isArrayRef && rvalIsRef) {
      backend.arrayRefDerefInsertFuture(outermostArray, arr, 
                                        ix, member);
    } else if (isArrayRef && !rvalIsRef) {
      backend.arrayRefInsertFuture(outermostArray, arr, 
                                        ix, member);
    } else if (!isArrayRef && rvalIsRef) {
      backend.arrayDerefInsertFuture(arr, ix, member);
    } else {
      assert(!isArrayRef && !rvalIsRef);
      backend.arrayInsertFuture(arr, ix, member);
    }
  }

  /**
   * Statement that evaluates an expression with no assignment E.g., trace()
   */
  private List<Var> exprStatement(Context context, SwiftAST tree) throws UserException {
    assert (tree.getChildCount() == 1);
    SwiftAST expr = tree.child(0);

    ExprType exprType = TypeChecker.findExprType(context, expr);
    backend.addComment("Swift l." + context.getLine() + " evaluating "
        + " expression and throwing away " + exprType.elems() +
        " results");

    // Need to create throwaway temporaries for return values
    List<Var> oList = new ArrayList<Var>();
    for (Type t : exprType.getTypes()) {
      oList.add(varCreator.createTmp(context, t));
    }

    exprWalker.evalToVars(context, expr, oList, null);
    return oList;
  }

  private void updateStmt(Context context, SwiftAST tree) 
        throws UserException {
    Update up = Update.fromAST(context, tree);
    Type exprType = up.typecheck(context);
    Var evaled = exprWalker.eval(context, up.getExpr(), exprType, false, null);
    backend.update(up.getTarget(), up.getMode(), evaled);
  }


  private void defineBuiltinFunction(Context context, SwiftAST tree)
  throws UserException {
    final int REQUIRED_CHILDREN = 5;
    assert(tree.getChildCount() >= REQUIRED_CHILDREN);
    String function  = tree.child(0).getText();
    SwiftAST typeParamsT = tree.child(1);
    SwiftAST outputs = tree.child(2);
    SwiftAST inputs  = tree.child(3);
    SwiftAST tclPackage = tree.child(4);
    assert(inputs.getType() == ExMParser.FORMAL_ARGUMENT_LIST);
    assert(outputs.getType() == ExMParser.FORMAL_ARGUMENT_LIST);
    assert(tclPackage.getType() == ExMParser.TCL_PACKAGE);
    assert(tclPackage.getChildCount() == 2);
    
    Set<String> typeParams = extractTypeParams(typeParamsT);

    FunctionDecl fdecl = FunctionDecl.fromAST(context, function, inputs,
                                              outputs, typeParams);
    
    FunctionType ft = fdecl.getFunctionType();
    LogHelper.debug(context, "builtin: " + function + " " + ft);
    
    String pkg = Literals.extractLiteralString(context, tclPackage.child(0)); 
    String version = Literals.extractLiteralString(context, tclPackage.child(1));
    backend.requirePackage(pkg, version);
    
    int pos = REQUIRED_CHILDREN;
    TclFunRef impl = null;
    if (pos < tree.getChildCount() && 
              tree.child(pos).getType() == ExMParser.TCL_FUN_REF) {
      SwiftAST tclImplRef = tree.child(pos);
      String symbol  = Literals.extractLiteralString(context, 
                                                     tclImplRef.child(0));
      impl = new TclFunRef(pkg, symbol, version);
      pos++;
    }
    
    TclOpTemplate inlineTcl = null;
    if (pos < tree.getChildCount() && 
          tree.child(pos).getType() == ExMParser.INLINE_TCL) {
      /* See if a template is provided for inline TCL code for function */
      SwiftAST inlineTclTree = tree.child(pos);
      inlineTcl = wrapper.loadTclTemplate(context, function, fdecl, ft,
                                          inlineTclTree);
      pos++;
    }
    
    // Read annotations at end of child list
    for (; pos < tree.getChildCount(); pos++) {
      handleFunctionAnnotation(context, function, tree.child(pos),
                                inlineTcl != null);
    }

    TaskMode taskMode = ForeignFunctions.getTaskMode(function);
    
    // TODO: assume for now that all non-local builtins are targetable
    // This is still not quite right (See issue #230)
    boolean isTargetable = false;
    if (!taskMode.isLocal()) {
      isTargetable = true;
      context.setFunctionProperty(function, FnProp.TARGETABLE);
    }
    
    context.defineFunction(function, ft);
    if (impl != null) {
      context.setFunctionProperty(function, FnProp.BUILTIN);
      backend.defineBuiltinFunction(function, ft, impl);
    } else {
      if (inlineTcl == null) {
        throw new UserException(context, "Must provide TCL implementation or " +
        		"inline TCL for function " + function);
      }
      // generate composite function wrapping inline tcl
      context.setFunctionProperty(function, FnProp.WRAPPED_BUILTIN);
      context.setFunctionProperty(function, FnProp.SYNC);
      boolean isParallel = context.hasFunctionProp(function, FnProp.PARALLEL);
      if (isParallel && taskMode != TaskMode.WORKER) {
        throw new UserException(context,
                        "Parallel tasks must execute on workers");
      }
      
      // Defer generation of wrapper until it is called
      wrapper.saveWrapper(function, ft, fdecl,
                          taskMode, isParallel, isTargetable);
    }
  }



  private Set<String> extractTypeParams(SwiftAST typeParamsT) {
    assert(typeParamsT.getType() == ExMParser.TYPE_PARAMETERS);
    Set<String> typeParams = new HashSet<String>();
    for (SwiftAST typeParam: typeParamsT.children()) {
      assert(typeParam.getType() == ExMParser.ID);
      typeParams.add(typeParam.getText());
    }
    return typeParams;
  }


  private void handleFunctionAnnotation(Context context, String function,
      SwiftAST annotTree, boolean hasLocalVersion) throws UserException {
    assert(annotTree.getType() == ExMParser.ANNOTATION);
    
    assert(annotTree.getChildCount() > 0);
    String key = annotTree.child(0).getText();
    if (annotTree.getChildCount() == 1) { 
      registerFunctionAnnotation(context, function, key);
    } else {
      assert(annotTree.getChildCount() == 2);
      String val = annotTree.child(1).getText();
      if (key.equals(Annotations.FN_BUILTIN_OP)) {
        addlocalEquiv(context, function, val);
      } else if (key.equals(Annotations.FN_STC_INTRINSIC)) {
        IntrinsicFunction intF;
        try {
          intF = IntrinsicFunction.valueOf(val.toUpperCase());
        } catch (IllegalArgumentException ex) {
          throw new InvalidAnnotationException(context, "Invalid intrinsic name: "
                + " " + val + ".  Expected one of: " + IntrinsicFunction.values());
        }
        context.addIntrinsic(function, intF);
      } else if (key.equals(Annotations.FN_IMPLEMENTS)) {
        SpecialFunction special = ForeignFunctions.findSpecialFunction(val);
        if (special == null) {
          throw new InvalidAnnotationException(context, "\"" + val +
              "\" is not the name of a specially handled function in STC. " +
              "Valid options are: " +
              StringUtil.concat(SpecialFunction.values())); 
        }
        ForeignFunctions.addSpecialImpl(special, function);
      } else if (key.equals(Annotations.FN_DISPATCH)) {
        try {
          TaskMode mode;
          if (val.equals("LEAF")) {
            // Renamed from LEAF to worker, keep this for compatibility
            mode = TaskMode.WORKER;
          } else { 
            mode = TaskMode.valueOf(val);
          }
          ForeignFunctions.addTaskMode(function, mode);
        } catch (IllegalArgumentException e) {
          throw new UserException(context, "Unknown dispatch mode " + val + ". "
              + " Valid options are: " + StringUtil.concat(TaskMode.values()));
        }
      } else {
        throw new InvalidAnnotationException(context, "Tcl function",
                                             key, true);
      }
    }
  }

  private void addlocalEquiv(Context context, String function, String val)
      throws UserException {
    BuiltinOpcode opcode;
    try {
      opcode = BuiltinOpcode.valueOf(val);
    } catch (IllegalArgumentException e) {
      throw new UserException(context, "Unknown builtin op " + val);
    }
    assert(opcode != null);
    ForeignFunctions.addOpEquiv(function, opcode);
  }

  /**
   * Check that an annotation for the named function is valid, and
   * add it to the known semantic info
   * @param function
   * @param annotation
   * @throws UserException 
   */
  private void registerFunctionAnnotation(Context context, String function,
                  String annotation) throws UserException {
    if (annotation.equals(Annotations.FN_ASSERTION)) {
      ForeignFunctions.addAssertVariable(function);
    } else if (annotation.equals(Annotations.FN_PURE)) {
      ForeignFunctions.addPure(function);
    } else if (annotation.equals(Annotations.FN_COMMUTATIVE)) {
      ForeignFunctions.addCommutative(function);
    } else if (annotation.equals(Annotations.FN_COPY)) {
      ForeignFunctions.addCopy(function);
    } else if (annotation.equals(Annotations.FN_MINMAX)) {
      ForeignFunctions.addMinMax(function);
    } else if (annotation.equals(Annotations.FN_PAR)) {
      context.setFunctionProperty(function, FnProp.PARALLEL);
    } else {
      throw new InvalidAnnotationException(context, "function", annotation, false);
    }
    
  }


  private void defineFunction(Context context, SwiftAST tree)
  throws UserException {
    context.syncFilePos(tree, lineMapping);
    String function = tree.child(0).getText();
    LogHelper.debug(context, "define function: " + context.getLocation() +
                              function);
    assert(tree.getChildCount() >= 5);
    SwiftAST typeParams = tree.child(1);
    SwiftAST outputs = tree.child(2);
    SwiftAST inputs = tree.child(3);
    
    assert(typeParams.getType() == ExMParser.TYPE_PARAMETERS);
    if (typeParams.getChildCount() != 0) {
      throw new UserException(context, "Cannot provide type parameters for "
                                      + "Swift functions");
    }
    
    List<String> annotations = extractFunctionAnnotations(context, tree, 5);
    
    FunctionDecl fdecl = FunctionDecl.fromAST(context, function, inputs,
                          outputs, Collections.<String>emptySet());
    FunctionType ft = fdecl.getFunctionType();
    
    if (ft.hasVarargs()) {
      throw new TypeMismatchException(context, "composite function cannot" +
              " have variable-length argument lists");
    }
    for (Type it: ft.getInputs()) {
      if (Types.isPolymorphic(it)) {
        throw new TypeMismatchException(context, "composite functions " +
                "cannot have polymorphic input argument types, such as: " + it);
      }
    }
    
    // Handle main as special case of regular function declaration
    if (function.equals(Constants.MAIN_FUNCTION) &&
        (ft.getInputs().size() > 0 || ft.getOutputs().size() > 0))
      throw new TypeMismatchException(context,
          "main() is not allowed to have input or output arguments");

    boolean async = true;
    for (String annotation: annotations) {
      if (annotation.equals(Annotations.FN_SYNC)) {
        async = false;
      } else {
        registerFunctionAnnotation(context, function, annotation);
      }
    }
    
    context.defineFunction(function, ft);
    context.setFunctionProperty(function, FnProp.COMPOSITE);
    if (!async) {
      context.setFunctionProperty(function, FnProp.SYNC);
    }
  }

  private List<String> extractFunctionAnnotations(Context context,
          SwiftAST tree, int firstChild) throws InvalidAnnotationException {
    List<String> annotations = new ArrayList<String>();
    for (SwiftAST subtree: tree.children(firstChild)) {
      context.syncFilePos(subtree, lineMapping);
      assert(subtree.getType() == ExMParser.ANNOTATION);
      assert(subtree.getChildCount() == 1 || subtree.getChildCount() == 2);
      String annotation = subtree.child(0).getText();
      if (subtree.getChildCount() == 2) {
        throw new InvalidAnnotationException(context, "Function defn",
                                             annotation, true);
      }
      annotations.add(annotation);
    }
    return annotations;
  }

  /** Compile the function, assuming it is already defined in context */
  private void compileFunction(Context context, SwiftAST tree)
                                            throws UserException {
    String function = tree.child(0).getText();
    LogHelper.debug(context, "compile function: starting: " + function );
    // defineFunction should already have been called
    assert(context.isFunction(function));
    assert(context.hasFunctionProp(function, FnProp.COMPOSITE));
    SwiftAST outputs = tree.child(2);
    SwiftAST inputs = tree.child(3);
    SwiftAST block = tree.child(4);

    FunctionDecl fdecl = FunctionDecl.fromAST(context, function, 
                  inputs, outputs, Collections.<String>emptySet());
    
    List<Var> iList = fdecl.getInVars();
    List<Var> oList = fdecl.getOutVars();
    
    // Analyse variable usage inside function and annotate AST
    context.syncFilePos(tree, lineMapping);
    varAnalyzer.analyzeVariableUsage(context, lineMapping, function,
                                     iList, oList, block);

    LocalContext functionContext = new LocalContext(context, function);
    functionContext.addDeclaredVariables(iList);
    functionContext.addDeclaredVariables(oList);
    
    TaskMode mode = context.hasFunctionProp(function, FnProp.SYNC) ?
                          TaskMode.SYNC : TaskMode.CONTROL;
    backend.startFunction(function, oList, iList, mode);
    block(functionContext, block);
    backend.endFunction();

    LogHelper.debug(context, "compile function: done: " + function);
  }

  private void defineAppFunction(Context context, SwiftAST tree)
      throws UserException {
    LogHelper.info(context.getLevel(), "defineAppFunction");
    assert(tree.getChildCount() >= 4);
    SwiftAST functionT = tree.child(0);
    assert(functionT.getType() == ExMParser.ID);
    String function = functionT.getText();
    SwiftAST outArgsT = tree.child(1);
    SwiftAST inArgsT = tree.child(2);
    
    FunctionDecl decl = FunctionDecl.fromAST(context, function, inArgsT,
                        outArgsT,   Collections.<String>emptySet());
    context.defineFunction(function, decl.getFunctionType());
    context.setFunctionProperty(function, FnProp.APP);
    context.setFunctionProperty(function, FnProp.SYNC);
    context.setFunctionProperty(function, FnProp.TARGETABLE);
  }

  private void compileAppFunction(Context context, SwiftAST tree)
      throws UserException {
    LogHelper.info(context.getLevel(), "compileAppFunction");
    assert(tree.getChildCount() >= 4);
    SwiftAST functionT = tree.child(0);
    assert(functionT.getType() == ExMParser.ID);
    String function = functionT.getText();
    SwiftAST outArgsT = tree.child(1);
    SwiftAST inArgsT = tree.child(2);
    SwiftAST appBodyT = tree.child(3);
    
    FunctionDecl decl = FunctionDecl.fromAST(context, function, inArgsT,
                        outArgsT,   Collections.<String>emptySet());
    List<Var> outArgs = decl.getOutVars();
    List<Var> inArgs = decl.getInVars();
    
    /* Pass in e.g. location */
    List<Var> realInArgs = new ArrayList<Var>();
    realInArgs.addAll(inArgs);
    TaskProps props = new TaskProps();
    // Need to pass location arg into task dispatch wait statement
    // Priority is passed implicitly
    Var loc = new Var(Types.V_INT, Var.DEREF_COMPILER_VAR_PREFIX + "location",
        Alloc.LOCAL, DefType.INARG);
    realInArgs.add(loc);
    props.put(TaskPropKey.LOCATION, loc.asArg());
    
    
    context.syncFilePos(tree, lineMapping);
    List<String> annotations = extractFunctionAnnotations(context, tree, 4);
    context.syncFilePos(tree, lineMapping);
    boolean hasSideEffects = true, deterministic = false;
    for (String annotation: annotations) {
      if (annotation.equals(Annotations.FN_PURE)) {
        hasSideEffects = false;
        deterministic = true;
      } else if (annotation.equals(Annotations.FN_SIDE_EFFECT_FREE)) {
        hasSideEffects = false;
      } else if (annotation.equals(Annotations.FN_DETERMINISTIC)) {
        deterministic = true;
      } else {
        throw new InvalidAnnotationException(context, "app function",
                                             annotation, false);
      }
    }
    
    LocalContext appContext = new LocalContext(context, function);
    appContext.addDeclaredVariables(outArgs);
    appContext.addDeclaredVariables(inArgs);
    
    
    backend.startFunction(function, outArgs, realInArgs, TaskMode.SYNC);
    genAppFunctionBody(appContext, appBodyT, inArgs, outArgs, 
                       hasSideEffects, deterministic, props);
    backend.endFunction();
  }


  /**
   * @param context local context for app function
   * @param cmd AST for app function command
   * @param outArgs output arguments for app
   * @param hasSideEffects
   * @param deterministic
   * @param props 
   * @throws UserException
   */
  private void genAppFunctionBody(Context context, SwiftAST appBody,
          List<Var> inArgs, List<Var> outArgs,
          boolean hasSideEffects,
          boolean deterministic, TaskProps props) throws UserException {
    //TODO: don't yet handle situation where user is naughty and
    //    uses output variable in expression context
    assert(appBody.getType() == ExMParser.APP_BODY);
    assert(appBody.getChildCount() >= 1);
    
    // Extract command from AST
    SwiftAST cmd = appBody.child(0);
    assert(cmd.getType() == ExMParser.COMMAND);
    assert(cmd.getChildCount() >= 1);
    SwiftAST appNameT = cmd.child(0);
    assert(appNameT.getType() == ExMParser.STRING);
    String appName = Literals.extractLiteralString(context, appNameT);
    
    // Evaluate any argument expressions
    List<Var> args = evalAppCmdArgs(context, cmd);
    
    // Process any redirections
    Redirects<Var> redirFutures = processAppRedirects(context,
                                                    appBody.children(1));
    
    checkAppOutputs(context, appName, outArgs, args, redirFutures);
    
    // Work out what variables must be closed before command line executes
    Pair<Map<String, Var>, List<Var>> wait =
            selectAppWaitVars(context, args, outArgs, redirFutures);
    Map<String, Var> fileNames = wait.val1; 
    List<Var> waitVars = wait.val2;
    
    // use wait to wait for data then dispatch task to worker
    String waitName = context.getFunctionContext().constructName("app-leaf");
    // do deep wait for array args
    backend.startWaitStatement(waitName, waitVars,
        WaitMode.TASK_DISPATCH, false, true, TaskMode.WORKER, props);
    // On worker, just execute the required command directly
    Pair<List<Arg>, Redirects<Arg>> retrieved = retrieveAppArgs(context,
                                          args, redirFutures, fileNames);
    List<Arg> localArgs = retrieved.val1; 
    Redirects<Arg> localRedirects = retrieved.val2;
    
    // Create dummy dependencies for input files to avoid wait
    // being optimised out
    List<Arg> localInFiles = new ArrayList<Arg>();
    for (Var inArg: inArgs) {
      if (Types.isFile(inArg.type())) {
        Var localInputFile = varCreator.fetchValueOf(context, inArg);
        localInFiles.add(Arg.createVar(localInputFile));
      }
    }
    
    // Declare local dummy output vars
    List<Var> localOutputs = new ArrayList<Var>(outArgs.size());
    for (Var output: outArgs) {
      Var localOutput = varCreator.createValueOfVar(context, output);
      localOutputs.add(localOutput);
      Arg localOutputFileName = null;
      if (Types.isFile(output.type())) {
        localOutputFileName = Arg.createVar(
            varCreator.fetchValueOf(context, fileNames.get(output.name())));

        // Initialize the output with a filename
        backend.initLocalOutFile(localOutput, localOutputFileName, output);
      }
    }
    
    backend.runExternal(appName, localArgs, localInFiles, localOutputs,
                        localRedirects, hasSideEffects, deterministic);
    
    for (int i = 0; i < outArgs.size(); i++) {
      Var output = outArgs.get(i);
      Var localOutput = localOutputs.get(i);
      if (Types.isFile(output.type())) {
        backend.assignFile(output, Arg.createVar(localOutput));
        if (output.isMapped() != Ternary.TRUE &&
            output.type().fileKind().supportsTmpImmediate()) {
          // Cleanup temporary local file if needed
          backend.decrLocalFileRef(localOutput); 
        }
      } else {
        assert(Types.isVoid(output.type()));
        backend.assignVoid(output, Arg.createVar(localOutput));
      }
    }
    backend.endWaitStatement();
  }

  private Redirects<Var> processAppRedirects(Context context,
                             List<SwiftAST> redirects) throws UserException {    
    Redirects<Var> redir = new Redirects<Var>();

    // Process redirections
    for (SwiftAST redirT: redirects) {
      context.syncFilePos(redirT, lineMapping);
      assert(redirT.getChildCount() == 2);
      SwiftAST redirType = redirT.child(0);
      SwiftAST redirExpr = redirT.child(1);
      String redirTypeName = LogHelper.tokName(redirType.getType());
      
      // Now typecheck
      Type type = TypeChecker.findSingleExprType(context, redirExpr);
      // TODO: maybe could have plain string for filename, e.g. /dev/null?
      if (!Types.isFile(type)) {
        throw new TypeMismatchException(context, "Invalid type for" +
            " app redirection, must be file: " + type.typeName());
      } else if (type.fileKind() != FileKind.LOCAL_FS) {
        throw new TypeMismatchException(context, "Cannot redirect " +
              redirTypeName + " to/from variable type " + type.typeName() + 
              ". Expected a regular file.");
      }
      
      Var result = exprWalker.eval(context, redirExpr, type, false, null);
      boolean mustBeOutArg = false;
      boolean doubleDefine = false;
      switch (redirType.getType()) {
        case ExMParser.STDIN:
          doubleDefine = redir.stdin != null;
          redir.stdin = result;
          break;
        case ExMParser.STDOUT:
          doubleDefine = redir.stdout != null;
          redir.stdout = result;
          break;
        case ExMParser.STDERR:
          doubleDefine = redir.stderr != null;
          redir.stderr = result;
          break;
        default:
          throw new STCRuntimeError("Unexpected token type: " +
                              LogHelper.tokName(redirType.getType())); 
      }
      if (result.defType() != DefType.OUTARG && mustBeOutArg) { 
        throw new UserException(context, redirTypeName + " parameter "
          + " must be output file");
      }

      if (doubleDefine) {
        throw new UserException(context, "Specified redirection " +
                redirTypeName + " more than once");
      }
    }

    return redir;
  }

  /**
   * Check that app output args are not omitted from command line
   * Omit warning
   * @param context
   * @param outputs
   * @param args
   * @param redir 
   * @throws UserException 
   */
  private void checkAppOutputs(Context context, String function,
      List<Var> outputs, List<Var> args, Redirects<Var> redirFutures)
                                                      throws UserException {
    boolean deferredError = false;
    HashMap<String, Var> outMap = new HashMap<String, Var>();
    for (Var output: outputs) {
      // Check output types
      if (!Types.isFile(output.type()) && !Types.isVoid(output.type())) {
        LogHelper.error(context, "Output argument " + output.name() + " has "
            + " invalid type for app output: " + output.type().typeName());
        deferredError = true;
      }
      outMap.put(output.name(), output);
    }
    if (redirFutures.stdout != null) {
      // Already typechecked
      Var output = redirFutures.stdout;
      outMap.put(output.name(), output);
    }
    
    for (Var arg: args) {
      if (arg.defType() == DefType.OUTARG) {
        outMap.remove(arg.name());
      }
    }
    for (Var redir: redirFutures.redirections(false, true)) {
      if (redir.defType() == DefType.OUTARG) {
        outMap.remove(redir.name());
      }
    }
    
    for (Var unreferenced: outMap.values()) {
      if (!Types.isVoid(unreferenced.type())) {
        LogHelper.warn(context, "Output argument " + unreferenced.name() 
          + " is not referenced in app command line");
      }
    }
    if (deferredError) {
      throw new UserException(context, "Compilation failed due to type "
          + "error in definition of function " + function);
    }
  }

  /**
   * Work out what the local args to the app function should be
   * @param context
   * @param args
   * @param fileNames
   * @return pair of the command line arguments, and local redirects
   * @throws UserException
   * @throws UndefinedTypeException
   * @throws DoubleDefineException
   */
  private Pair<List<Arg>, Redirects<Arg>> retrieveAppArgs(Context context,
          List<Var> args, Redirects<Var> redirFutures,
          Map<String, Var> fileNames)
          throws UserException, UndefinedTypeException, DoubleDefineException {
    List<Arg> localInputs = new ArrayList<Arg>();
    for (Var in: args) {
      localInputs.add(Arg.createVar(retrieveAppArg(context, fileNames, in)));
    }
    Redirects<Arg> redirValues = new Redirects<Arg>();
    if (redirFutures.stdin != null) {
      redirValues.stdin = Arg.createVar(retrieveAppArg(context, fileNames,
                                                 redirFutures.stdin));
    }
    if (redirFutures.stdout != null) {
      redirValues.stdout = Arg.createVar(retrieveAppArg(context, fileNames,
                                                 redirFutures.stdout));
    }
    if (redirFutures.stderr != null) {
      redirValues.stderr = Arg.createVar(retrieveAppArg(context, fileNames,
                                                 redirFutures.stderr));
    }
    
    return Pair.create(localInputs, redirValues);
  }


  private Var
      retrieveAppArg(Context context, Map<String, Var> fileNames, Var in)
          throws UserException, UndefinedTypeException, DoubleDefineException {
    Var localInput;
    if (Types.isFile(in.type())) {
      Var filenameFuture = fileNames.get(in.name());
      assert(filenameFuture != null);
      localInput = varCreator.fetchValueOf(context, filenameFuture);
    } else if (Types.isArray(in.type())) {
      // Pass array reference directly
      localInput = in;
    } else {
      localInput = varCreator.fetchValueOf(context, in);
    }
    return localInput;
  }

  /**
   * Evaluates argument expressions for app command line
   * @param context
   * @param cmdArgs
   * @return
   * @throws TypeMismatchException
   * @throws UserException
   */
  private List<Var> 
      evalAppCmdArgs(Context context, SwiftAST cmdArgs) 
          throws TypeMismatchException, UserException {
    List<Var> args = new ArrayList<Var>();
    // Skip first arg: that is id
    for (SwiftAST cmdArg: cmdArgs.children(1)) {
      if (cmdArg.getType() == ExMParser.APP_FILENAME) {
        assert(cmdArg.getChildCount() == 1);
        String fileVarName = cmdArg.child(0).getText();
        Var file = context.lookupVarUser(fileVarName);
        if (!Types.isFile(file.type())) {
          throw new TypeMismatchException(context, "Variable " + file.name()
                  + " is not a file, cannot use @ prefix for app");
        }
        args.add(file);
      } else {
        Type exprType = TypeChecker.findSingleExprType(context, cmdArg);
        Type baseType; // Type after expanding arrays
        if (Types.isArray(exprType)) {
          ArrayInfo info = new ArrayInfo(exprType);
          baseType = info.baseType;
        } else if (Types.isRef(exprType)) {
          // TODO
          throw new STCRuntimeError("TODO: support reference types on " +
          		"app cmd line");
        } else {
          baseType = exprType;
        }
        if (Types.isString(baseType) || Types.isInt(baseType) ||
            Types.isFloat(baseType) || Types.isBool(baseType) ||
            Types.isFile(baseType)) {
            args.add(exprWalker.eval(context, cmdArg, exprType, false, null));
        } else {
          throw new TypeMismatchException(context, "Cannot convert type " +
                        baseType.typeName() + " to app command line arg");
        }
      }
    }
    return args;
  }

  /**
   * Choose which inputs/outputs to an app invocation should be blocked
   * upon.  This is somewhat complex since we sometimes need to block
   * on filenames/file statuses/etc  
   * @param context
   * @param redirFutures 
   * @param inputs
   * @param outputs
   * @return
   * @throws UserException
   * @throws UndefinedTypeException
   */
  private Pair<Map<String, Var>, List<Var>> selectAppWaitVars(
          Context context, List<Var> args, List<Var> outArgs,
          Redirects<Var> redirFutures)
                                                throws UserException,
          UndefinedTypeException {
    List<Var> allArgs = new ArrayList<Var>();
    allArgs.addAll(args);
    allArgs.addAll(redirFutures.redirections(true, true));
    
    // map from file var to filename
    Map<String, Var> fileNames = new HashMap<String, Var>(); 
    List<Var> waitVars = new ArrayList<Var>();
    for (Var arg: allArgs) {
      if (Types.isFile(arg.type())) {
        if (fileNames.containsKey(arg.name())) {
          continue;
        }
        loadAppFilename(context, fileNames, waitVars, arg);
      } else {
        waitVars.add(arg);
      }
    }
    // Fetch missing output arguments that weren't on command line
    for (Var outArg: outArgs) {
      if (Types.isFile(outArg.type()) && !fileNames.containsKey(outArg.name())) {
        loadAppFilename(context, fileNames, waitVars, outArg);
      }
    }
    
    return Pair.create(fileNames, waitVars);
  }


  private void loadAppFilename(Context context, Map<String, Var> fileNames,
                               List<Var> waitVars, Var fileVar)
      throws UserException, UndefinedTypeException {
    // Need to wait for filename for files
    Var filenameFuture = varCreator.createFilenameAlias(context, fileVar);

    if (fileVar.defType() == DefType.OUTARG &&
        fileVar.type().fileKind().supportsTmpImmediate()) {
      // If output may be unmapped, need to assign file name
      backend.getFileName(filenameFuture, fileVar, true);
    } else {
      backend.getFileName(filenameFuture, fileVar, false);
    }
    waitVars.add(filenameFuture);
    if (fileVar.defType() != DefType.OUTARG) {
      // Don't wait for file to be closed for output arg
      waitVars.add(fileVar);
    }

    fileNames.put(fileVar.name(), filenameFuture);
  }


  private void defineNewType(Context context, SwiftAST defnTree,
                             boolean aliasOnly) throws UserException {
    assert (defnTree.getType() == ExMParser.DEFINE_NEW_TYPE ||
            defnTree.getType() == ExMParser.TYPEDEF );
    int children = defnTree.getChildCount();
    assert(children >= 2);
    String typeName = defnTree.child(0).getText();
    String baseTypeName = defnTree.child(1).getText();
    
    Type baseType = context.lookupTypeUser(baseTypeName);
    
    for (int i = defnTree.getChildCount() - 1; i >= 2; i--) {
      SwiftAST arrayT = defnTree.child(i);
      assert(arrayT.getType() == ExMParser.ARRAY);
      Type keyType = VariableDeclaration.getArrayKeyType(context, arrayT);
      baseType = new ArrayType(keyType, baseType);
    }
    
    Type newType;
    if (aliasOnly) {
      newType = baseType;
    } else {
      newType = new SubType(baseType, typeName);
    }
    
    context.defineType(typeName, newType);
  }


  private void defineNewStructType(Context context, SwiftAST defnTree)
      throws UserException {
    assert (defnTree.getType() == ExMParser.DEFINE_NEW_STRUCT_TYPE);
    int children = defnTree.getChildCount();
    if (children < 1) {
      throw new STCRuntimeError("expected DEFINE_NEW_TYPE to have at "
          + "least one child");
    }
    String typeName = defnTree.child(0).getText();

    // Build the type from the fields
    ArrayList<StructField> fields = new ArrayList<StructField>(children - 1);

    HashSet<String> usedFieldNames = new HashSet<String>(children - 1);
    for (int i = 1; i < children; i++) {
      SwiftAST fieldTree = defnTree.child(i);
      assert (fieldTree.getType() == ExMParser.STRUCT_FIELD_DEF);
      assert(fieldTree.getChildCount() >= 2);
      assert(fieldTree.child(0).getType() == ExMParser.ID);
      assert(fieldTree.child(1).getType() == ExMParser.ID);
      String baseTypeName = fieldTree.child(0).getText();
      Type fieldType = context.lookupTypeUnsafe(baseTypeName);
      if (fieldType == null) {
        throw new UndefinedTypeException(context, baseTypeName);
      }
      String name = fieldTree.child(1).getText();

      // Account for any [] 
      for (int j = 2; j < fieldTree.getChildCount(); j++) {
        SwiftAST arrayT = fieldTree.child(j);
        assert(arrayT.getType() == ExMParser.ARRAY);
        // Work out the key type
        Type keyType = VariableDeclaration.getArrayKeyType(context, arrayT);
        fieldType = new Types.ArrayType(keyType, fieldType);
      }
      if (usedFieldNames.contains(name)) {
        throw new DoubleDefineException(context, "Field " + name
            + " is defined twice in type" + typeName);
      }
      fields.add(new StructField(fieldType, name));
      usedFieldNames.add(name);
    }

    StructType newType = new StructType(typeName, fields);
    context.defineType(typeName, newType);
    backend.defineStructType(newType);
    LogHelper.debug(context, "Defined new type called " + typeName + ": "
        + newType.toString());
  }

  private String endOfFile(Context context, SwiftAST tree) {
    return "# EOF";
  }

  private void globalConst(Context context, SwiftAST tree) 
        throws UserException {
    assert(tree.getType() == ExMParser.GLOBAL_CONST);
    assert(tree.getChildCount() == 1);
    
    SwiftAST varTree = tree.child(0);
    assert(varTree.getType() == ExMParser.DECLARATION);
    
    VariableDeclaration vd = VariableDeclaration.fromAST(context,
                    varTree);
    assert(vd.count() == 1);
    VariableDescriptor vDesc = vd.getVar(0);
    if (vDesc.getMappingExpr() != null) {
      throw new UserException(context, "Can't have mapped global constant");
    }
    Var v = context.declareVariable(vDesc.getType(), vDesc.getName(),
                   Alloc.GLOBAL_CONST, DefType.GLOBAL_CONST, null);
    
    
    SwiftAST val = vd.getVarExpr(0);
    assert(val != null);
    
    Type valType = TypeChecker.findSingleExprType(context, val);
    if (!valType.assignableTo(v.type())) {
      throw new TypeMismatchException(context, "trying to assign expression "
          + " of type " + valType.typeName() + " to global constant " 
          + v.name() + " which has type " + v.type());
    }
    
    String msg = "Don't support non-literal "
        + "expressions for global constants";
    switch (v.type().primType()) {
    case BOOL:
      String bval = Literals.extractBoolLit(context, val);
      if (bval == null) {
        throw new UserException(context, msg);
      }
      backend.addGlobal(v.name(), Arg.createBoolLit(
                                  Boolean.parseBoolean(bval)));
      break;
    case INT:
      Long ival = Literals.extractIntLit(context, val);
      if (ival == null) {
        throw new UserException(context, msg);
      }
      backend.addGlobal(v.name(), Arg.createIntLit(ival));
      break;
    case FLOAT:
      Double fval = Literals.extractFloatLit(context, val);
      if (fval == null) {
        Long sfval = Literals.extractIntLit(context, val); 
        if (sfval == null) {
          throw new UserException(context, msg);
        } else {
          fval = Literals.interpretIntAsFloat(context, sfval);
        }
      }
      assert(fval != null);
      backend.addGlobal(v.name(), Arg.createFloatLit(fval));
      break;
    case STRING:
      String sval = Literals.extractStringLit(context, val);
      if (sval == null) {
        throw new UserException(context, msg);
      }
      backend.addGlobal(v.name(), Arg.createStringLit(sval));
      break;
    default:
      throw new STCRuntimeError("Unexpect value tree type in "
          + " global constant: " + LogHelper.tokName(val.getType()));
    }
  }
}
