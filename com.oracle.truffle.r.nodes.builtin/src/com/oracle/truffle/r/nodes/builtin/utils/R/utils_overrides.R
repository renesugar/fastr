# Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 3 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 3 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 3 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.

eval(expression({
setBreakpoint <- function (srcfile, line, nameonly = TRUE, envir = parent.frame(), 
    lastenv, verbose = TRUE, tracer, print = FALSE, clear = FALSE, 
    ...) 
{
    res <- .fastr.setBreakpoint(srcfile, line, clear)
    if(is.null(res))
    	res <- structure(list(), class="findLineNumResult")
    if (verbose) 
        print(res, steps = !clear)
}
}), asNamespace("utils"))

eval(expression({
index.search.orig <- utils:::index.search 
index.search <- function (topic, paths, firstOnly = FALSE) 
{
    res <- index.search.orig(topic, paths, firstOnly)
    
    if(length(res) == 0) {
        fastrHelpRd <- .fastr.helpPath(topic)
        if(!is.null(fastrHelpRd)) {
            res <- fastrHelpRd
        }
    }
    res
}
}), asNamespace("utils"))

eval(expression({
.getHelpFile.orig <- utils:::.getHelpFile
.getHelpFile <- function (file) 
{
    fastrHelpRd <- .fastr.helpRd(file)
    if(!is.null(fastrHelpRd)) {
        return(tools::parse_Rd(textConnection(fastrHelpRd)))
    }

    .getHelpFile.orig(file)
}
}), asNamespace("utils"))