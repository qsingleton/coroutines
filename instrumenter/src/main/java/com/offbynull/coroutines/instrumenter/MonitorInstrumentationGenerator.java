/*
 * Copyright (c) 2015, Kasra Faghihi, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.offbynull.coroutines.instrumenter;

import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.call;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.construct;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.empty;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.forEach;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.loadNull;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.loadVar;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.merge;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.monitorEnter;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.monitorExit;
import static com.offbynull.coroutines.instrumenter.asm.InstructionGenerationUtils.saveVar;
import static com.offbynull.coroutines.instrumenter.asm.SearchUtils.searchForOpcodes;
import com.offbynull.coroutines.instrumenter.asm.VariableTable.Variable;
import com.offbynull.coroutines.user.LockState;
import com.offbynull.coroutines.user.MethodState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.reflect.ConstructorUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

final class MonitorInstrumentationGenerator {

    private static final Method METHODSTATE_GETLOCKSTATE_METHOD
            = MethodUtils.getAccessibleMethod(MethodState.class, "getLockState");
    private static final Constructor<LockState> LOCKSTATE_INIT_METHOD
            = ConstructorUtils.getAccessibleConstructor(LockState.class);
    private static final Method LOCKSTATE_ENTER_METHOD
            = MethodUtils.getAccessibleMethod(LockState.class, "enter", Object.class);
    private static final Method LOCKSTATE_EXIT_METHOD
            = MethodUtils.getAccessibleMethod(LockState.class, "exit", Object.class);
    private static final Method LOCKSTATE_TOARRAY_METHOD
            = MethodUtils.getAccessibleMethod(LockState.class, "toArray");
    

    private final MethodNode methodNode;
    private final Variable counterVar;
    private final Variable arrayLenVar;
    private final Variable lockStateVar;
    private final Variable methodStateVar;

    
    MonitorInstrumentationGenerator(MethodNode methodNode,
            MonitorInstrumentationVariables monitorInstrumentationVariables) {
        Validate.notNull(methodNode);
        Validate.notNull(monitorInstrumentationVariables);

        this.methodNode = methodNode;

        counterVar = monitorInstrumentationVariables.getCounterVar();
        arrayLenVar = monitorInstrumentationVariables.getArrayLenVar();
        lockStateVar = monitorInstrumentationVariables.getLockStateVar();
        methodStateVar = monitorInstrumentationVariables.getMethodStateVar();
    }
    
    MonitorInstrumentationInstructions generate() {
        // Find monitorenter/monitorexit and create replacement instructions that keep track of which objects were entered/exited.
        //
        //
        // Check for synchronized code blocks. Synchronized methods and Java 5 locks are okay to ignore, but synchronized blocks need to
        // be instrumented. For every MONITORENTER instruction that's performed on an object, there needs to be an equivalent MONITOREXIT
        // before the method exits. Otherwise, the method throws an IllegalMonitorState exception when it exits. I've confirmed by making a
        // simple java class in JASMIN assembler that did 10 MONITORENTERs in a loop and returned. That code threw
        // IllegalMonitorStateException when it tried to return. This link seems to explain it pretty well:
        // http://mail.openjdk.java.net/pipermail/hotspot-runtime-dev/2008-April/000118.html.
        //
        // So that means that if we're going to instrument the code to suspend and return in places, we need to make sure to exit all
        // monitors when we return and to re-enter those monitors when we come back in. We can't handle this via static analysis like we do
        // with inspecting the operand stack and local variables. It looks like static analysis is what Javaflow tries to do, which will
        // work in 99% of cases but won't in some edge cases like if the code was written in some JVM langauge other than Java.
        //
        // The following code creates replacements for every MONITORENTER and MONITOREXIT instruction such that those monitors get tracked
        // in a LockState object.
        List<AbstractInsnNode> monitorInsnNodes = searchForOpcodes(methodNode.instructions, Opcodes.MONITORENTER, Opcodes.MONITOREXIT);
        Map<AbstractInsnNode, InsnList> monitorInsnNodeReplacements = new HashMap<>();
        
        
        // IMPORTANT NOTE: The following code only generates code if monitorInsnNodes is NOT empty. That means that there has to be at least
        // one MONITORENTER or one MONITOREXIT for this method to generate instructions. Otherwise, all instruction listings will be stubbed
        // out with empty instruction lists.
        
        for (AbstractInsnNode monitorInsnNode : monitorInsnNodes) {
            InsnNode insnNode = (InsnNode) monitorInsnNode;
            InsnList replacementLogic;
            
            switch (insnNode.getOpcode()) {
                case Opcodes.MONITORENTER: {
                    Type clsType = Type.getType(LOCKSTATE_ENTER_METHOD.getDeclaringClass());
                    Type methodType = Type.getType(LOCKSTATE_ENTER_METHOD);
                    String clsInternalName = clsType.getInternalName();
                    String methodDesc = methodType.getDescriptor();
                    String methodName = LOCKSTATE_ENTER_METHOD.getName();

                    // NOTE: This adds to the lock state AFTER locking.
                    replacementLogic
                            = merge(
                                                                                             // [obj]
                                    new InsnNode(Opcodes.DUP),                               // [obj, obj]
                                    new InsnNode(Opcodes.MONITORENTER),                      // [obj]
                                    new VarInsnNode(Opcodes.ALOAD, lockStateVar.getIndex()), // [obj, lockState]
                                    new InsnNode(Opcodes.SWAP),                              // [lockState, obj]
                                    new MethodInsnNode(Opcodes.INVOKEVIRTUAL,                // []
                                            clsInternalName,
                                            methodName,
                                            methodDesc,
                                            false)
                            );
                    break;
                }
                case Opcodes.MONITOREXIT: {
                    Type clsType = Type.getType(LOCKSTATE_EXIT_METHOD.getDeclaringClass());
                    Type methodType = Type.getType(LOCKSTATE_EXIT_METHOD);
                    String clsInternalName = clsType.getInternalName();
                    String methodDesc = methodType.getDescriptor();
                    String methodName = LOCKSTATE_EXIT_METHOD.getName();

                    // NOTE: This adds to the lock state AFTER unlocking.
                    replacementLogic
                            = merge(
                                                                                             // [obj]
                                    new InsnNode(Opcodes.DUP),                               // [obj, obj]
                                    new InsnNode(Opcodes.MONITOREXIT),                       // [obj]
                                    new VarInsnNode(Opcodes.ALOAD, lockStateVar.getIndex()), // [obj, lockState]
                                    new InsnNode(Opcodes.SWAP),                              // [lockState, obj]
                                    new MethodInsnNode(Opcodes.INVOKEVIRTUAL,                // []
                                            clsInternalName,
                                            methodName,
                                            methodDesc,
                                            false)
                            );
                    break;
                }
                default:
                    throw new IllegalStateException(); // should never happen
            }
            
            monitorInsnNodeReplacements.put(monitorInsnNode, replacementLogic);
        }
        
        
        // Create code to create a new lockstate object
        InsnList createAndStoreLockStateInsnList;
        if (monitorInsnNodeReplacements.isEmpty()) { // if we don't have any MONITORENTER/MONITOREXIT, ignore this
            createAndStoreLockStateInsnList = empty();
        } else {
            createAndStoreLockStateInsnList
                    = merge(
                            construct(LOCKSTATE_INIT_METHOD),
                            saveVar(lockStateVar)
                    );
        }
        
        
        // Create code to load lockstate object from methodstate
        InsnList loadAndStoreLockStateFromMethodStateInsnList;
        if (monitorInsnNodeReplacements.isEmpty()) {
            loadAndStoreLockStateFromMethodStateInsnList = empty();
        } else {
            loadAndStoreLockStateFromMethodStateInsnList
                    = merge(
                            call(METHODSTATE_GETLOCKSTATE_METHOD, loadVar(methodStateVar)),
                            saveVar(lockStateVar)
                    );
        }

        
        // Create code to load lockstate object to the stack
        InsnList loadLockStateToStackInsnList;
        if (monitorInsnNodeReplacements.isEmpty()) {
            loadLockStateToStackInsnList = loadNull();
        } else {
            loadLockStateToStackInsnList = loadVar(lockStateVar);
        }

        
        // Create code to enter all monitors in lockstate object
        InsnList enterMonitorsInLockStateInsnList;
        if (monitorInsnNodeReplacements.isEmpty()) {
            enterMonitorsInLockStateInsnList = empty();
        } else {
            enterMonitorsInLockStateInsnList
                    = forEach(counterVar, arrayLenVar,
                            call(LOCKSTATE_TOARRAY_METHOD, loadVar(lockStateVar)),
                            merge(
                                    monitorEnter() // temp monitor enter
                            )
                    );
        }

        

        // Create code to exit all monitors in lockstate object
        InsnList exitMonitorsInLockStateInsnList;
        if (monitorInsnNodeReplacements.isEmpty()) {
            exitMonitorsInLockStateInsnList = empty();
        } else {
            exitMonitorsInLockStateInsnList
                    = forEach(counterVar, arrayLenVar,
                            call(LOCKSTATE_TOARRAY_METHOD, loadVar(lockStateVar)),
                                    merge(
                                    monitorExit() // temp monitor exit
                            )
                    );
        }
        
        return new MonitorInstrumentationInstructions(monitorInsnNodeReplacements,
                createAndStoreLockStateInsnList,
                loadAndStoreLockStateFromMethodStateInsnList,
                loadLockStateToStackInsnList,
                enterMonitorsInLockStateInsnList,
                exitMonitorsInLockStateInsnList);
    }
}
