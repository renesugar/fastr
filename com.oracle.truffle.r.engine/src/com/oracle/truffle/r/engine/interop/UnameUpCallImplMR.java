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
package com.oracle.truffle.r.engine.interop;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.CanResolve;
import com.oracle.truffle.api.interop.MessageResolution;
import com.oracle.truffle.api.interop.Resolve;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.r.engine.TruffleRLanguage;
import com.oracle.truffle.r.engine.interop.ffi.nfi.TruffleNFI_Base;

@MessageResolution(receiverType = TruffleNFI_Base.TruffleNFI_UnameNode.UnameUpCallImpl.class, language = TruffleRLanguage.class)
public class UnameUpCallImplMR {
    @CanResolve
    public abstract static class UnameUpCallImplCheck extends Node {

        protected static boolean test(TruffleObject receiver) {
            return receiver instanceof TruffleNFI_Base.TruffleNFI_UnameNode.UnameUpCallImpl;
        }
    }

    @Resolve(message = "EXECUTE")
    public abstract static class UnameUpCallImplExecute extends Node {
        protected Object access(@SuppressWarnings("unused") VirtualFrame frame, TruffleNFI_Base.TruffleNFI_UnameNode.UnameUpCallImpl receiver, Object[] arguments) {
            receiver.unameUpCall((String) arguments[0], (String) arguments[1], (String) arguments[2], (String) arguments[3], (String) arguments[4]);
            return receiver;
        }
    }

}