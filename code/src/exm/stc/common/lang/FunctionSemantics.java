package exm.stc.common.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import exm.stc.common.exceptions.STCRuntimeError;
import exm.stc.common.lang.Operators.BuiltinOpcode;
import exm.stc.common.util.MultiMap;
import exm.stc.common.util.MultiMap.ListFactory;

/**
 * Static class to track info about semantics of built-in functions.
 * 
 * Currently it is sufficient to have this as a static class.
 */
public class FunctionSemantics {
  
  /** Names of built-ins which don't have side effects */
  private static HashSet<String> pure = new HashSet<String>();
  
  /** Names of built-ins which have a local equivalent operation */
  private static HashMap<String, BuiltinOpcode>
            localEquivalents = new HashMap<String, BuiltinOpcode>();

  /** inverse of localEquivalents */
  private static MultiMap<BuiltinOpcode, String> localEquivalentsInv
          = new MultiMap<BuiltinOpcode, String>(new ListFactory<String>() {
            public List<String> make() { // Will only have one entry most of time
              return new ArrayList<String>(1);
            }
          });
  
  /** Built-ins which are known to be deterministic */
  private static HashSet<String> commutative = 
                                      new HashSet<String>();

  /**
   * Functions which just copy value of input to output
   */
  private static HashSet<String> copyFunctions = new HashSet<String>();
  private static HashSet<String> minMaxFunctions = new HashSet<String>();
  
  
  /**
   * Templates for inline tcl code that can be used to generate
   * local implementation of function
   */
  private static HashMap<String, TclOpTemplate> inlineTemplates
    = new HashMap<String, TclOpTemplate>();
  
  public static void addPure(String builtinFunction) {
    pure.add(builtinFunction);
  }
  
  public static boolean isPure(String builtinFunction) {
    return pure.contains(builtinFunction);
  }
  

  public static void addLocalEquiv(String builtinFunction, BuiltinOpcode op) {
    localEquivalents.put(builtinFunction, op);
    localEquivalentsInv.put(op, builtinFunction);
  }
  
  public static boolean hasLocalEquiv(String builtinFunction) {
    return localEquivalents.containsKey(builtinFunction);
  }
  
  public static BuiltinOpcode getLocalEquiv(String builtinFunction) {
    return localEquivalents.get(builtinFunction);
  }
  
  /**
   * Find an implementation of a built-in op
   */
  public static List<String> findOpImpl(BuiltinOpcode op) {
    return localEquivalentsInv.get(op);
  }
  
  public static void addCommutative(String builtInFunction) {
    commutative.add(builtInFunction);
  }
  
  public static boolean isCommutative(String builtinFunction) {
    return commutative.contains(builtinFunction);
  }
  
  public static void addCopy(String builtInFunction) {
    copyFunctions.add(builtInFunction);
  }
  
  public static boolean isCopyFunction(String builtinFunction) {
    return copyFunctions.contains(builtinFunction);
  }
  
  public static void addMinMax(String builtInFunction) {
    minMaxFunctions.add(builtInFunction);
  }
  
  public static boolean isMinMaxFunction(String builtinFunction) {
    return minMaxFunctions.contains(builtinFunction);
  }
  
  public static void addAssertVariable(String builtinFunction) {
    assertVariants.add(builtinFunction);
  }
  
  /** Keep track of assert variants so they can be disabled as an optimization */
  private static final HashSet<String> assertVariants = new
                HashSet<String>();

  /**
   * @param fnName true if the named builtin is some kind of assert statemetn
   * @return
   */
  public static boolean isAssertVariant(String fnName) {
    return assertVariants.contains(fnName);
  }
  
  
  public static void addInlineTemplate(String fnName, TclOpTemplate tmp) {
    inlineTemplates.put(fnName, tmp);
  }
  
  public static boolean hasLocalVersion(String fnName) {
    return inlineTemplates.containsKey(fnName);
  }
  
  public static TclOpTemplate getInlineTemplate(String fnName) {
    return inlineTemplates.get(fnName);
  }
  
  public static class TemplateElem {
    public static enum ElemKind {
      TEXT,
      VARIABLE
    }
    
    private final ElemKind kind;
    private final String contents;
    
    private TemplateElem(ElemKind kind, String contents) {
      super();
      this.kind = kind;
      this.contents = contents;
    }
    
    public static TemplateElem createTok(String text) {
      return new TemplateElem(ElemKind.TEXT, text);
    }
    
    public static TemplateElem createVar(String varName) {
      return new TemplateElem(ElemKind.VARIABLE, varName);
    }
    
    public ElemKind getKind() {
      return kind;
    }
    
    public String getText() {
      if (kind == ElemKind.TEXT) {
        return contents;
      } else {
        throw new STCRuntimeError("not text, was: " + kind); 
      }
    }
    
    public String getVarName() {
      if (kind == ElemKind.VARIABLE) {
        return contents;
      } else {
        throw new STCRuntimeError("not var, was: " + kind); 
      }
    }
    
    public String toString() {
      if (kind == ElemKind.VARIABLE) {
        return contents;
      } else {
        assert(kind == ElemKind.TEXT);
        return "\"" + contents + "\"";
      }
    }
  }
  
  public static class TclOpTemplate {
    private final ArrayList<TemplateElem> elems = 
                              new ArrayList<TemplateElem>();
    
    /**
     * Names of positional input variables for template
     */
    private final ArrayList<String> outNames =
                              new ArrayList<String>();
    
    /**
     * Names of positional output variables for template
     */
    private final ArrayList<String> inNames =
                              new ArrayList<String>();
    
    public boolean addInName(String e) {
      return inNames.add(e);
    }

    public boolean addInNames(Collection<? extends String> c) {
      return inNames.addAll(c);
    }

    public boolean addOutName(String e) {
      return outNames.add(e);
    }

    public boolean addOutNames(Collection<? extends String> c) {
      return outNames.addAll(c);
    }
    
    public List<String> getInNames() {
      return Collections.unmodifiableList(inNames);
    }
    
    public List<String> getOutNames() {
      return Collections.unmodifiableList(outNames);
    }

    public void addElem(TemplateElem elem) {
      elems.add(elem);
    }
    
    public List<TemplateElem> getElems() {
      return Collections.unmodifiableList(elems);
    }

    public String toString() {
      return elems.toString();
    }
  }
}