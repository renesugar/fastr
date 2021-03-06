/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 3 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.engine.interop;

import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.ForeignAccess.StandardFactory;
import com.oracle.truffle.api.interop.KeyInfo;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.r.engine.TruffleRLanguageImpl;
import com.oracle.truffle.r.engine.interop.RAbstractVectorAccessFactoryFactory.VectorKeyInfoImplNodeGen;
import com.oracle.truffle.r.engine.interop.RAbstractVectorAccessFactoryFactory.VectorKeyInfoRootNodeGen;
import com.oracle.truffle.r.engine.interop.RAbstractVectorAccessFactoryFactory.VectorReadImplNodeGen;
import com.oracle.truffle.r.engine.interop.RAbstractVectorAccessFactoryFactory.VectorWriteImplNodeGen;
import com.oracle.truffle.r.nodes.access.vector.ElementAccessMode;
import com.oracle.truffle.r.nodes.access.vector.ExtractVectorNode;
import com.oracle.truffle.r.nodes.access.vector.ReplaceVectorNode;
import com.oracle.truffle.r.nodes.access.vector.ReplaceVectorNodeGen;
import com.oracle.truffle.r.nodes.control.RLengthNode;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.data.RLogical;
import com.oracle.truffle.r.runtime.data.RObject;
import com.oracle.truffle.r.runtime.data.RRaw;
import com.oracle.truffle.r.runtime.data.RScalar;
import com.oracle.truffle.r.runtime.data.RString;
import com.oracle.truffle.r.runtime.data.model.RAbstractAtomicVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractLogicalVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractRawVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.interop.Foreign2R;
import com.oracle.truffle.r.runtime.interop.Foreign2RNodeGen;
import com.oracle.truffle.r.runtime.interop.R2Foreign;
import com.oracle.truffle.r.runtime.interop.R2ForeignNodeGen;
import com.oracle.truffle.r.runtime.interop.RObjectNativeWrapper;
import com.oracle.truffle.r.runtime.nodes.RSyntaxNode;

abstract class InteropRootNode extends RootNode {
    InteropRootNode() {
        super(TruffleRLanguageImpl.getCurrentLanguage());
    }

    @Override
    public final SourceSection getSourceSection() {
        return RSyntaxNode.INTERNAL;
    }
}

public final class RAbstractVectorAccessFactory implements StandardFactory {

    abstract static class VectorReadImplNode extends InteropRootNode {

        @Child private ExtractVectorNode extract;
        @Child private R2Foreign r2Foreign;

        private final ConditionProfile unknownIdentifier = ConditionProfile.createBinaryProfile();

        @Override
        public final Object execute(VirtualFrame frame) {
            Object indentifier = ForeignAccess.getArguments(frame).get(0);
            Object receiver = ForeignAccess.getReceiver(frame);
            return execute(frame, receiver, indentifier);
        }

        protected abstract Object execute(VirtualFrame frame, Object reciever, Object indentifier);

        @Specialization
        protected Object readIndexed(VirtualFrame frame, Object receiver, int idx,
                        @Cached("createKeyInfoNode()") VectorKeyInfoImplNode keyInfo) {
            int info = keyInfo.execute(frame, receiver, idx);
            if (unknownIdentifier.profile(!KeyInfo.isExisting(info))) {
                CompilerDirectives.transferToInterpreter();
                throw UnknownIdentifierException.raise("" + idx);
            }
            return read(receiver, new Object[]{idx + 1});
        }

        @Specialization
        protected Object readIndexed(VirtualFrame frame, Object receiver, long idx,
                        @Cached("createKeyInfoNode()") VectorKeyInfoImplNode keyInfo) {
            int info = keyInfo.execute(frame, receiver, idx);
            if (unknownIdentifier.profile(!KeyInfo.isExisting(info))) {
                CompilerDirectives.transferToInterpreter();
                throw UnknownIdentifierException.raise("" + idx);
            }
            return read(receiver, new Object[]{idx + 1});
        }

        private Object read(Object receiver, Object[] positions) {
            if (extract == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                extract = insert(ExtractVectorNode.create(ElementAccessMode.SUBSCRIPT, true));
            }
            Object value = extract.apply(receiver, positions, RLogical.TRUE, RLogical.TRUE);
            if (r2Foreign == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                r2Foreign = insert(R2ForeignNodeGen.create());
            }
            return r2Foreign.execute(value);
        }

        @Fallback
        protected Object read(@SuppressWarnings("unused") Object receiver, Object indentifier) {
            CompilerDirectives.transferToInterpreter();
            throw UnknownIdentifierException.raise("" + indentifier);
        }

        protected VectorKeyInfoImplNode createKeyInfoNode() {
            return VectorKeyInfoImplNodeGen.create();
        }
    }

    abstract static class VectorWriteImplNode extends InteropRootNode {
        @Child private ReplaceVectorNode replace;
        @Child private Foreign2R foreign2R;

        @Override
        public final Object execute(VirtualFrame frame) {
            List<Object> arguments = ForeignAccess.getArguments(frame);
            Object receiver = ForeignAccess.getReceiver(frame);
            return execute(frame, receiver, arguments.get(0), arguments.get(1));
        }

        protected abstract Object execute(VirtualFrame frame, Object receiver, Object identifier, Object valueObj);

        @Specialization
        protected Object write(RAbstractRawVector receiver, int idx, byte valueObj) {
            return writeRaw(receiver, new Object[]{idx + 1}, valueObj);
        }

        @Specialization
        protected Object write(RAbstractRawVector receiver, long idx, byte valueObj) {
            return writeRaw(receiver, new Object[]{idx + 1}, valueObj);
        }

        private Object writeRaw(RAbstractRawVector receiver, Object[] positions, byte valueObj) {
            if (replace == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                replace = insert(ReplaceVectorNodeGen.create(ElementAccessMode.SUBSCRIPT, false, true, true));
            }
            return replace.apply(receiver, positions, RRaw.valueOf(valueObj));
        }

        @Specialization
        protected Object write(RAbstractLogicalVector receiver, int idx, byte valueObj) {
            return writeLogical(receiver, new Object[]{idx + 1}, valueObj);
        }

        @Specialization
        protected Object write(RAbstractLogicalVector receiver, long idx, byte valueObj) {
            return writeLogical(receiver, new Object[]{idx + 1}, valueObj);
        }

        protected static boolean isIntNA(int valueObj) {
            return RRuntime.isNA(valueObj);
        }

        @Specialization(guards = "isIntNA(valueObj)")
        protected Object writeLogicalNA(RAbstractLogicalVector receiver, int idx, @SuppressWarnings("unused") int valueObj) {
            return writeLogical(receiver, new Object[]{idx + 1}, RRuntime.LOGICAL_NA);
        }

        @Specialization(guards = "!isIntNA(valueObj)")
        protected Object writeLogicalNonNA(RAbstractLogicalVector receiver, int idx, int valueObj) {
            return writeLogical(receiver, new Object[]{idx + 1}, (byte) valueObj);
        }

        @Specialization(guards = "isIntNA(valueObj)")
        protected Object writeLogicalNA(RAbstractLogicalVector receiver, long idx, @SuppressWarnings("unused") int valueObj) {
            return writeLogical(receiver, new Object[]{idx + 1}, RRuntime.LOGICAL_NA);
        }

        @Specialization(guards = "!isIntNA(valueObj)")
        protected Object writeLogicalNonNA(RAbstractLogicalVector receiver, long idx, int valueObj) {
            return writeLogical(receiver, new Object[]{idx + 1}, (byte) valueObj);
        }

        private Object writeLogical(RAbstractLogicalVector receiver, Object[] positions, byte valueObj) {
            if (replace == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                replace = insert(ReplaceVectorNodeGen.create(ElementAccessMode.SUBSCRIPT, false, true, true));
            }
            return replace.apply(receiver, positions, valueObj);
        }

        @Specialization
        protected Object write(TruffleObject receiver, int idx, Object valueObj) {
            // idx + 1 R is indexing from 1
            return write(receiver, new Object[]{idx + 1}, valueObj);
        }

        @Specialization
        protected Object write(TruffleObject receiver, long idx, Object valueObj) {
            // idx + 1 R is indexing from 1
            return write(receiver, new Object[]{idx + 1}, valueObj);
        }

        private Object write(TruffleObject receiver, Object[] positions, Object valueObj) {
            if (foreign2R == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                foreign2R = insert(Foreign2RNodeGen.create());
            }
            Object value = foreign2R.execute(valueObj);
            if (replace == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                replace = insert(ReplaceVectorNodeGen.create(ElementAccessMode.SUBSCRIPT, false, true, true));
            }
            return replace.apply(receiver, positions, value);
        }

        @Fallback
        protected Object write(@SuppressWarnings("unused") Object receiver, Object field, @SuppressWarnings("unused") Object object) {
            CompilerDirectives.transferToInterpreter();
            throw UnknownIdentifierException.raise("" + field);
        }
    }

    abstract static class VectorKeyInfoRootNode extends InteropRootNode {
        @Child private VectorKeyInfoImplNode keyInfoNode = VectorKeyInfoImplNodeGen.create();

        @Override
        public final Object execute(VirtualFrame frame) {
            Object indentifier = ForeignAccess.getArguments(frame).get(0);
            Object receiver = ForeignAccess.getReceiver(frame);
            return execute(frame, receiver, indentifier);
        }

        protected abstract int execute(VirtualFrame frame, Object reciever, Object indentifier);

        @Specialization
        protected int keyInfo(VirtualFrame frame, Object receiver, Object indentifier) {
            return keyInfoNode.execute(frame, receiver, indentifier);
        }
    }

    abstract static class VectorKeyInfoImplNode extends Node {
        @Child private RLengthNode lengthNode = RLengthNode.create();
        private final ConditionProfile unknownIdentifier = ConditionProfile.createBinaryProfile();

        protected abstract int execute(VirtualFrame frame, Object reciever, Object indentifier);

        @Specialization
        protected int keyInfo(Object receiver, int idx) {
            return keyInfo(receiver, (long) idx);
        }

        @Specialization
        protected int keyInfo(Object receiver, long idx) {
            if (unknownIdentifier.profile(idx < 0 || idx >= lengthNode.executeInteger(receiver))) {
                return 0;
            }
            return KeyInfo.READABLE | KeyInfo.MODIFIABLE;
        }

        @Fallback
        protected int keyInfo(@SuppressWarnings("unused") Object receiver, @SuppressWarnings("unused") Object label) {
            return 0;
        }
    }

    @Override
    public CallTarget accessIsNull() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                return false;
            }
        });
    }

    @Override
    public CallTarget accessIsExecutable() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                return false;
            }
        });
    }

    @Override
    public CallTarget accessIsInstantiable() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                return false;
            }
        });
    }

    @Override
    public CallTarget accessIsBoxed() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                RAbstractVector arg = (RAbstractVector) ForeignAccess.getReceiver(frame);
                return arg.getLength() == 1 && isUnBoxable(arg);
            }
        });
    }

    private static boolean isUnBoxable(RAbstractVector vector) {
        Object o = vector.getDataAtAsObject(0);
        return isPrimitive(o);
    }

    private static boolean isPrimitive(Object element) {
        if (element == null) {
            return false;
        }
        final Class<?> elementType = element.getClass();
        return elementType == String.class || elementType == Character.class || elementType == Boolean.class || elementType == Byte.class || elementType == Short.class ||
                        elementType == Integer.class || elementType == Long.class || elementType == Float.class || elementType == Double.class;
    }

    @Override
    public CallTarget accessHasSize() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                return true;
            }
        });
    }

    @Override
    public CallTarget accessHasKeys() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                return false;
            }
        });
    }

    @Override
    public CallTarget accessGetSize() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {

            @Child private RLengthNode lengthNode = RLengthNode.create();

            @Override
            public Object execute(VirtualFrame frame) {
                return lengthNode.executeInteger(ForeignAccess.getReceiver(frame));
            }
        });
    }

    @Override
    public CallTarget accessUnbox() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                RAbstractVector arg = (RAbstractVector) ForeignAccess.getReceiver(frame);
                if (arg.getLength() == 1) {
                    return arg.getDataAtAsObject(0);
                } else {
                    throw UnsupportedMessageException.raise(Message.UNBOX);
                }
            }
        });
    }

    @Override
    public CallTarget accessRead() {
        return Truffle.getRuntime().createCallTarget(VectorReadImplNodeGen.create());
    }

    @Override
    public CallTarget accessWrite() {
        return Truffle.getRuntime().createCallTarget(VectorWriteImplNodeGen.create());
    }

    @Override
    public CallTarget accessExecute(int argumentsLength) {
        return null;
    }

    @Override
    public CallTarget accessInvoke(int argumentsLength) {
        return null;
    }

    @Override
    public CallTarget accessMessage(Message unknown) {
        return null;
    }

    @Override
    public CallTarget accessNew(int argumentsLength) {
        return null;
    }

    @Override
    public CallTarget accessKeys() {
        return null;
    }

    @Override
    public CallTarget accessKeyInfo() {
        return Truffle.getRuntime().createCallTarget(VectorKeyInfoRootNodeGen.create());
    }

    @Override
    public CallTarget accessIsPointer() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                return false;
            }
        });
    }

    @Override
    public CallTarget accessToNative() {
        return Truffle.getRuntime().createCallTarget(new InteropRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                RAbstractVector arg = (RAbstractVector) ForeignAccess.getReceiver(frame);
                return new RObjectNativeWrapper((RObject) arg);
            }
        });
    }

    static class Check extends RootNode {

        Check() {
            super(null);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            final Object receiver = ForeignAccess.getReceiver(frame);
            // TODO: RLogical has no ForeignAccess, issue: GR-9536, use RAbstractVectorAccessFactory
            // for compatibility.
            final boolean logical = receiver.getClass() == RLogical.class;
            // TODO: RString has no ForeignAccess, issue: GR-9536, use RAbstractVectorAccessFactory
            // for compatibility.
            final boolean string = receiver.getClass() == RString.class;
            return receiver instanceof RAbstractAtomicVector && (!(receiver instanceof RScalar) || logical || string);
        }
    }
}
