/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.cf.taste.impl.similarity;

import org.apache.mahout.cf.taste.common.Refreshable;
import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.similarity.UserSimilarity;
import org.apache.mahout.cf.taste.similarity.PreferenceInferrer;
import org.apache.mahout.cf.taste.impl.common.FastMap;
import org.apache.mahout.cf.taste.impl.common.IteratorIterable;
import org.apache.mahout.cf.taste.impl.common.IteratorUtils;
import org.apache.mahout.cf.taste.impl.common.RandomUtils;
import org.apache.mahout.cf.taste.impl.recommender.TopItems;
import org.apache.mahout.cf.taste.model.DataModel;
import org.apache.mahout.cf.taste.model.User;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public final class GenericUserSimilarity implements UserSimilarity {

  private final Map<User, Map<User, Double>> similarityMaps = new FastMap<User, Map<User, Double>>();

  public GenericUserSimilarity(Iterable<UserUserSimilarity> similarities) {
    initSimilarityMaps(similarities);
  }

  public GenericUserSimilarity(Iterable<UserUserSimilarity> similarities, int maxToKeep) {
    Iterable<UserUserSimilarity> keptSimilarities = TopItems.getTopUserUserSimilarities(maxToKeep, similarities);
    initSimilarityMaps(keptSimilarities);
  }

  public GenericUserSimilarity(UserSimilarity otherSimilarity, DataModel dataModel) throws TasteException {
    List<? extends User> users = IteratorUtils.iterableToList(dataModel.getUsers());
    Iterator<UserUserSimilarity> it = new DataModelSimilaritiesIterator(otherSimilarity, users);
    initSimilarityMaps(new IteratorIterable<UserUserSimilarity>(it));
  }

  public GenericUserSimilarity(UserSimilarity otherSimilarity, DataModel dataModel, int maxToKeep)
          throws TasteException {
    List<? extends User> users = IteratorUtils.iterableToList(dataModel.getUsers());
    Iterator<UserUserSimilarity> it = new DataModelSimilaritiesIterator(otherSimilarity, users);
    Iterable<UserUserSimilarity> keptSimilarities =
            TopItems.getTopUserUserSimilarities(maxToKeep, new IteratorIterable<UserUserSimilarity>(it));
    initSimilarityMaps(keptSimilarities);
  }

  private void initSimilarityMaps(Iterable<UserUserSimilarity> similarities) {
    for (UserUserSimilarity uuc : similarities) {
      User similarityUser1 = uuc.getUser1();
      User similarityUser2 = uuc.getUser2();
      int compare = similarityUser1.compareTo(similarityUser2);
      if (compare != 0) {
        // Order them -- first key should be the "smaller" one
        User user1;
        User user2;
        if (compare < 0) {
          user1 = similarityUser1;
          user2 = similarityUser2;
        } else {
          user1 = similarityUser2;
          user2 = similarityUser1;
        }
        Map<User, Double> map = similarityMaps.get(user1);
        if (map == null) {
          map = new FastMap<User, Double>();
          similarityMaps.put(user1, map);
        }
        map.put(user2, uuc.getValue());
      }
      // else similarity between user and itself already assumed to be 1.0
    }
  }

  @Override
  public double userSimilarity(User user1, User user2) {
    int compare = user1.compareTo(user2);
    if (compare == 0) {
      return 1.0;
    }
    User first;
    User second;
    if (compare < 0) {
      first = user1;
      second = user2;
    } else {
      first = user2;
      second = user1;
    }
    Map<User, Double> nextMap = similarityMaps.get(first);
    if (nextMap == null) {
      return Double.NaN;
    }
    Double similarity = nextMap.get(second);
    return similarity == null ? Double.NaN : similarity;
  }

  @Override
  public void setPreferenceInferrer(PreferenceInferrer inferrer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void refresh(Collection<Refreshable> alreadyRefreshed) {
    // Do nothing
  }

  public static final class UserUserSimilarity implements Comparable<UserUserSimilarity> {

    private final User user1;
    private final User user2;
    private final double value;

    public UserUserSimilarity(User user1, User user2, double value) {
      if (user1 == null || user2 == null) {
        throw new IllegalArgumentException("A user is null");
      }
      if (Double.isNaN(value) || value < -1.0 || value > 1.0) {
        throw new IllegalArgumentException("Illegal value: " + value);
      }
      this.user1 = user1;
      this.user2 = user2;
      this.value = value;
    }

    public User getUser1() {
      return user1;
    }

    public User getUser2() {
      return user2;
    }

    public double getValue() {
      return value;
    }

    @Override
    public String toString() {
      return "UserUserSimilarity[" + user1 + ',' + user2 + ':' + value + ']';
    }

    /**
     * Defines an ordering from highest similarity to lowest.
     */
    @Override
    public int compareTo(UserUserSimilarity other) {
      double otherValue = other.value;
      return value > otherValue ? -1 : value < otherValue ? 1 : 0;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof UserUserSimilarity)) {
        return false;
      }
      UserUserSimilarity otherSimilarity = (UserUserSimilarity) other;
      return otherSimilarity.user1.equals(user1) && otherSimilarity.user2.equals(user2) && otherSimilarity.value == value;
    }

    @Override
    public int hashCode() {
      return user1.hashCode() ^ user2.hashCode() ^ RandomUtils.hashDouble(value);
    }

  }

  private static final class DataModelSimilaritiesIterator implements Iterator<UserUserSimilarity> {

    private final UserSimilarity otherSimilarity;
    private final List<? extends User> users;
    private final int size;
    private int i;
    private User user1;
    private int j;

    private DataModelSimilaritiesIterator(UserSimilarity otherSimilarity, List<? extends User> users) {
      this.otherSimilarity = otherSimilarity;
      this.users = users;
      this.size = users.size();
      i = 0;
      user1 = users.get(0);
      j = 1;
    }

    @Override
    public boolean hasNext() {
      return i < size - 1;
    }

    @Override
    public UserUserSimilarity next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      User user2 = users.get(j);
      double similarity;
      try {
        similarity = otherSimilarity.userSimilarity(user1, user2);
      } catch (TasteException te) {
        // ugly:
        throw new RuntimeException(te);
      }
      UserUserSimilarity result = new UserUserSimilarity(user1, user2, similarity);
      j++;
      if (j == size) {
        i++;
        user1 = users.get(i);
        j = i + 1;
      }
      return result;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

  }

}