//! X11 wire encoding: byte-order aware reader over one request, writer into a client's output.

use std::fmt;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Order {
    Le,
    Be,
}

/// A request shorter than what its handler reads (the Java code hit BufferUnderflowException).
#[derive(Debug, PartialEq, Eq)]
pub struct Underflow;

impl fmt::Display for Underflow {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("request too short")
    }
}

pub type Res<T> = Result<T, Underflow>;

/// Cursor over the bytes of exactly one request (after the 4-byte header).
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
    order: Order,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8], order: Order) -> Reader<'a> {
        Reader { buf, pos: 0, order }
    }

    pub fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    pub fn pos(&self) -> usize {
        self.pos
    }

    fn take(&mut self, n: usize) -> Res<&'a [u8]> {
        if self.remaining() < n {
            return Err(Underflow);
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    pub fn u8(&mut self) -> Res<u8> {
        Ok(self.take(1)?[0])
    }

    pub fn i8(&mut self) -> Res<i8> {
        Ok(self.u8()? as i8)
    }

    pub fn u16(&mut self) -> Res<u16> {
        let b = self.take(2)?;
        let a = [b[0], b[1]];
        Ok(match self.order {
            Order::Le => u16::from_le_bytes(a),
            Order::Be => u16::from_be_bytes(a),
        })
    }

    pub fn i16(&mut self) -> Res<i16> {
        Ok(self.u16()? as i16)
    }

    pub fn u32(&mut self) -> Res<u32> {
        let b = self.take(4)?;
        let a = [b[0], b[1], b[2], b[3]];
        Ok(match self.order {
            Order::Le => u32::from_le_bytes(a),
            Order::Be => u32::from_be_bytes(a),
        })
    }

    pub fn i32(&mut self) -> Res<i32> {
        Ok(self.u32()? as i32)
    }

    pub fn skip(&mut self, n: usize) -> Res<()> {
        self.take(n).map(|_| ())
    }

    /// Everything that is left, without consuming it past the end of the request.
    pub fn rest(&mut self) -> &'a [u8] {
        let s = &self.buf[self.pos..];
        self.pos = self.buf.len();
        s
    }

    pub fn bytes(&mut self, n: usize) -> Res<&'a [u8]> {
        self.take(n)
    }

    /// STRING8 of `len` bytes followed by padding to a multiple of 4 (Latin-1).
    pub fn string8(&mut self, len: usize) -> Res<String> {
        let b = self.take(len)?;
        let pad = (4 - len % 4) % 4;
        // The Java stream skipped the padding unconditionally; a short request is an error there too.
        self.skip(pad)?;
        Ok(b.iter().map(|&c| c as char).collect())
    }
}

/// Appends X11 structures to a byte vector in the client's byte order.
pub struct Writer<'a> {
    pub out: &'a mut Vec<u8>,
    order: Order,
}

impl<'a> Writer<'a> {
    pub fn new(out: &'a mut Vec<u8>, order: Order) -> Writer<'a> {
        Writer { out, order }
    }

    pub fn u8(&mut self, v: u8) -> &mut Self {
        self.out.push(v);
        self
    }

    pub fn u16(&mut self, v: u16) -> &mut Self {
        match self.order {
            Order::Le => self.out.extend_from_slice(&v.to_le_bytes()),
            Order::Be => self.out.extend_from_slice(&v.to_be_bytes()),
        }
        self
    }

    pub fn i16(&mut self, v: i16) -> &mut Self {
        self.u16(v as u16)
    }

    pub fn u32(&mut self, v: u32) -> &mut Self {
        match self.order {
            Order::Le => self.out.extend_from_slice(&v.to_le_bytes()),
            Order::Be => self.out.extend_from_slice(&v.to_be_bytes()),
        }
        self
    }

    pub fn u64(&mut self, v: u64) -> &mut Self {
        match self.order {
            Order::Le => self.out.extend_from_slice(&v.to_le_bytes()),
            Order::Be => self.out.extend_from_slice(&v.to_be_bytes()),
        }
        self
    }

    pub fn pad(&mut self, n: usize) -> &mut Self {
        self.out.resize(self.out.len() + n, 0);
        self
    }

    pub fn bytes(&mut self, b: &[u8]) -> &mut Self {
        self.out.extend_from_slice(b);
        self
    }

    /// STRING8 plus padding to a multiple of 4 (Latin-1: every char is one byte).
    pub fn string8(&mut self, s: &str) -> &mut Self {
        let n = s.chars().count();
        self.out.extend(s.chars().map(|c| c as u32 as u8));
        self.pad((4 - n % 4) % 4)
    }
}
