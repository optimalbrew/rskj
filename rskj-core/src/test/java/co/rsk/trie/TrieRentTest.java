/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.trie;

import co.rsk.crypto.Keccak256;
import org.ethereum.crypto.Keccak256Helper;
import org.ethereum.vm.GasCost;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;


/**
  * Created by #mish on April 21, 2020. 
 */
public class TrieRentTest {
    
    // trie put, get node and r 
    @Test
    public void putKeyGetRent() {
        Trie trie = new Trie();        
        trie = trie.put("foo", "abc".getBytes());
        trie = trie.put("foot", "abc".getBytes());
        //System.out.println(trie);
        System.out.println("Rent fully paid until time  "+ trie.getLastRentPaidTime());
        
        // replace with findNode?
        List<Trie> nodes = trie.getNodes("foo");

        Assert.assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), nodes.get(0).getValue());
        Assert.assertEquals(-1,trie.getLastRentPaidTime()); // 0 (long cannot be null)
    }

    // Different node versions with put and putwithRent.
    // Also checks node version for internal node created by split 
    @Test
    public void nodeVersionTest() {
        Trie trie = new Trie();        
        trie = trie.put("foo", "leaf".getBytes()); // node version 1
        trie = trie.putWithRent("fo", "1stepUp".getBytes(), 2020L); // nodeversion 2
        trie = trie.put("f", "2stepsUp".getBytes()); // nodeversion 1 again
        trie = trie.putWithRent("bar", "createsplit".getBytes(), 2021L); // nodeversion 2
   
        // walk up the tree
        List<Trie> nodes = trie.getNodes("foo"); 
        /*
        System.out.println("Node version number: "+ nodes.get(0).getNodeVersionNumber() + " LRPT: " + nodes.get(0).getLastRentPaidTime());
        System.out.println("Node version number: "+ nodes.get(1).getNodeVersionNumber() + " LRPT: " + nodes.get(1).getLastRentPaidTime());
        System.out.println("Node version number: "+ nodes.get(2).getNodeVersionNumber() + " LRPT: " + nodes.get(2).getLastRentPaidTime());
        System.out.println("Node version number: "+ nodes.get(3).getNodeVersionNumber() + " LRPT: " + nodes.get(3).getLastRentPaidTime());
        */
        // check nodeVersions
        Assert.assertEquals(nodes.get(0).getNodeVersionNumber(),1);
        Assert.assertEquals(nodes.get(1).getNodeVersionNumber(),2);
        Assert.assertEquals(nodes.get(2).getNodeVersionNumber(),1);
        Assert.assertEquals(nodes.get(3).getNodeVersionNumber(),2); // internal root node
        
        //check rent timestamps
        Assert.assertEquals(nodes.get(0).getLastRentPaidTime(),-1);
        Assert.assertEquals(nodes.get(1).getLastRentPaidTime(),2020);
        Assert.assertEquals(nodes.get(2).getLastRentPaidTime(),-1);
        Assert.assertEquals(nodes.get(3).getLastRentPaidTime(),2021); //internal root node timestamp
    }

    // base: a test modified from TrieKeyValueTest -> save, retrieve, check rent status
    @Test
    public void putAndGetKeyValueTwice() {
        Trie trie = new Trie();
        Trie trie1 = trie.put("foo".getBytes(), "bar".getBytes());
        System.out.println("Rent fully paid until time Trie 1: "+ trie1.getLastRentPaidTime());
        
        Assert.assertNotNull(trie1.get("foo"));
        Assert.assertArrayEquals("bar".getBytes(), trie1.get("foo".getBytes()));

        Trie trie2 = trie1.putWithRent("foo".getBytes(), "zip".getBytes(), 1000_000); 
        Assert.assertNotNull(trie2.get("foo"));
        Assert.assertArrayEquals("zip".getBytes(), trie2.get("foo".getBytes()));
        System.out.println("Rent fully paid until time Trie 2: "+ trie2.getLastRentPaidTime());
        //Assert.assertSame(trie1, trie2);
    }
    
    @Test
    public void putAndGetKeyValueSameTrie() {
        Trie trie = new Trie();
        trie = trie.put("foo".getBytes(), "bar".getBytes());
        System.out.println("Rent fully paid until time Trie : " + trie.getLastRentPaidTime() +
                        " value:  " + new String(trie.get("foo".getBytes())) );
         //System.out.println(new String("bar".getBytes()));
        //add same key with rent update
        trie = trie.putWithRent("foo".getBytes(), "zip".getBytes(), 1000_000); 
        System.out.println("Rent fully paid until time (same key with rentupdate): " + trie.getLastRentPaidTime() +
                        " value:  " + new String( trie.get("foo".getBytes())));
          
        //back to initial value, same key. RentpaidDatewill be not be retained
        trie = trie.put("foo".getBytes(), "bar2".getBytes());
        System.out.println("Rent fully paid until time (still the same key, no rent info): "+ trie.getLastRentPaidTime() +
                        " value:  " + new String( trie.get("foo".getBytes())));
        Assert.assertEquals(-1, trie.getLastRentPaidTime());       

    }
    
    // similar to above, but now the first put contains rent info.. so uses putWithRent() first, instead of put()
    @Test
    public void putLRPTAndGetKeyValueSameKey() {
        Trie trie = new Trie();
        trie = trie.putWithRent("foo".getBytes(), "zip".getBytes(), 1000_000); 
        System.out.println("Rent fully paid until time (same key with rentupdate): " + trie.getLastRentPaidTime() +
                        " value:  " + new String( trie.get("foo".getBytes())));
          
        //back to initial value, same key. RentpadDatewill be retained
        trie = trie.put("foo".getBytes(), "bar2".getBytes());
        System.out.println("Rent fully paid until time (still the same key, no rent info): "+ trie.getLastRentPaidTime() +
                        " value:  " + new String( trie.get("foo".getBytes())));
        Assert.assertEquals(-1, trie.getLastRentPaidTime());       

    }


    // #mish: this is a variant of a test of the same name from TrieGetNodesTest.
    @Test
    public void putKeyAndSubkeyAndGetNodes() {
        Trie trie = new Trie();
        //order of trie puts() does not matter for getNodes()
        trie = trie.put("foo".getBytes(), "abc".getBytes()); // "foo": main key of interest

        trie = trie.putWithRent("fo".getBytes(), "longSubKeyVal".getBytes(), 4000); //a longer subkey
        trie = trie.putWithRent("fo".getBytes(), "longSubKeyVal".getBytes(), 3000); // value unchanged, rent changed
        //trie = trie.putWithRent("fo".getBytes(), "newlongSubKeyVal".getBytes(), 3000); //value and changed, rent unchanged 
        
        trie = trie.putWithRent("f".getBytes(), "shortSubKeyVal".getBytes(), 100); //short subkey "f", even empty "" works
        trie = trie.putWithRent("f".getBytes(), "newshortSubKeyVal".getBytes(), 200); //value and rent both changed
        //regular put, this will not alter last rent paid time
        trie = trie.put("f".getBytes(), "newInfo".getBytes()); //value changed, but this should not change last rent paid time
        
        List<Trie> nodes = trie.getNodes("foo"); //foo, then fo, then f

        for (int n = 0; n < nodes.size(); n++){
            //System.out.println("Rent last paid through block: " + nodes.get(n).getLastRentPaidTime());
            System.out.println("Rent last paid time: "+ nodes.get(n).getLastRentPaidTime() + " value: " + new String(nodes.get(n).getValue()));
        }
        Assert.assertEquals(-1, nodes.get(0).getLastRentPaidTime()); // foo renttime not added
        Assert.assertEquals(3000, nodes.get(1).getLastRentPaidTime()); // fo 
        Assert.assertEquals("newInfo",new String(nodes.get(2).getValue())); // f updated value
        Assert.assertEquals(-1, nodes.get(2).getLastRentPaidTime()); // even though value updated, last rent paid status unchanged    
    }

    @Test
    public void computeRentGas() {
        Trie trie = new Trie();
        trie = trie.putWithRent("foo".getBytes(), "must pay rent or hibernate dodo!".getBytes(), 4000L);
        System.out.println("Value length (in bytes) " + trie.getValueLength());
        //long sixMonths = 6 * 30 * 24 *3600L;
        // rent due for 6 months = (32 bytes + 128 bytes overhead)  * 6*30*24*3600 seconds / (2^21) 
        //System.out.println(GasCost.calculateStorageRent(trie.getValueLength(),-200000L)); // error negative timedelta
        Assert.assertEquals(1186L, GasCost.calculateStorageRent(trie.getValueLength(), GasCost.SIX_MONTHS));
    }


}
