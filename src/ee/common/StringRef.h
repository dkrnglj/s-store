/* This file is part of VoltDB.
 * Copyright (C) 2008-2011 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

/* Copyright (C) 2017 by S-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Portland State University 
 *
 * Author: S-Store Team (sstore.cs.brown.edu)
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#ifndef STRINGREF_H
#define STRINGREF_H

#include <cstddef>

namespace voltdb
{
    class Pool;

    /// An object to use in lieu of raw char* pointers for strings
    /// which are not inlined into tuple storage.  This provides a
    /// constant value to live in tuple storage while allowing the memory
    /// containing the actual string to be moved around as the result of
    /// compaction.
    class StringRef
    {
    public:
        friend class CompactingStringPool;
        /// Create and return a new StringRef object which points to an
        /// allocated memory block of the requested size.  The caller
        /// may provide an optional Pool from which the memory (and
        /// the memory for the StringRef object itself) will be
        /// allocated, intended for temporary strings.  If no Pool
        /// object is provided, the StringRef and the string memory will be
        /// allocated out of the ThreadLocalPool.
        static StringRef* create(std::size_t size,
                                 Pool* dataPool = NULL);

        /// Destroy the given StringRef object and free any memory, if
        /// any, allocated from pools to store the object.
        /// sref must have been allocated and returned by a call to
        /// StringRef::create() and must not have been created in a
        /// temporary Pool
        static void destroy(StringRef* sref);

        char* get();
        const char* get() const;

    private:
        StringRef(std::size_t size);
        StringRef(std::size_t size, Pool* dataPool);
        ~StringRef();

        /// Callback used via the back-pointer in order to update the
        /// pointer to the memory backing this string reference
        void updateStringLocation(void* location);

        void setBackPtr();

        std::size_t m_size;
        bool m_tempPool;
        char* m_stringPtr;
    };
}

#endif // STRINGREF_H
