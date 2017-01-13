/*
 * Copyright (c) 2014, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.nodes.ffi;

import java.util.function.Supplier;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.r.runtime.RInternalError;
import com.oracle.truffle.r.runtime.context.RContext;

public final class FFIUpCallRootNode extends RootNode {
    private static RootCallTarget[] rootCallTargets = new RootCallTarget[UpCallsIndex.TABLE_LENGTH];

    @Child private FFIUpCallNode theFFIUpCallNode;
    private final int numArgs;

    private FFIUpCallRootNode(FFIUpCallNode child) {
        super(RContext.getRRuntimeASTAccess().getTruffleRLanguage(), null, new FrameDescriptor());
        theFFIUpCallNode = child;
        this.numArgs = child.numArgs();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        switch (numArgs) {
            case 0:
                return ((FFIUpCallNode.Arg0) theFFIUpCallNode).executeObject();
            case 1:
                return ((FFIUpCallNode.Arg1) theFFIUpCallNode).executeObject(args[0]);
            case 2:
                return ((FFIUpCallNode.Arg2) theFFIUpCallNode).executeObject(args[0], args[1]);
            case 3:
                return ((FFIUpCallNode.Arg3) theFFIUpCallNode).executeObject(args[0], args[1], args[2]);
            default:
                throw RInternalError.shouldNotReachHere();
        }
    }

    static void add(int index, Supplier<FFIUpCallNode> constructor) {
        FFIUpCallRootNode rootNode = new FFIUpCallRootNode(constructor.get());
        rootCallTargets[index] = Truffle.getRuntime().createCallTarget(rootNode);
    }

    public static RootCallTarget getCallTarget(int index) {
        RootCallTarget target = rootCallTargets[index];
        assert target != null;
        return target;
    }

}
